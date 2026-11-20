@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.update

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.local.personaltoolbox.BuildConfig
import com.local.personaltoolbox.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val version: String,
    val notes: String,
    val browserDownloadUrl: String,
    val assetApiUrl: String,
    val sha256: String?,
)

data class UpdateState(
    val status: String,
    val busy: Boolean = false,
    val release: ReleaseInfo? = null,
    val installReady: Boolean = false,
)

private data class DownloadTarget(val url: String, val authenticated: Boolean = false)

/** Application-scoped: downloads and state survive Activity recreation. No networking on the UI thread. */
class OtaUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val saved = context.getSharedPreferences("ota_download", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(UpdateState(context.getString(R.string.update_idle)))
    val state = mutableState.asStateFlow()
    private var foreground = WeakReference<Activity>(null)
    private var checkedOnLaunch = false
    private var processingDownload = false

    fun checkOnLaunch() {
        if (checkedOnLaunch) return
        checkedOnLaunch = true
        checkForUpdates()
    }

    fun checkForUpdates() {
        if (state.value.busy || saved.getLong(DOWNLOAD_ID, -1) != -1L) return
        if (UpdateConfig.GITHUB_OWNER == "YOUR_GITHUB_USERNAME" || UpdateConfig.GITHUB_OWNER.isBlank()) {
            mutableState.value = UpdateState(context.getString(R.string.update_not_configured))
            return
        }
        mutableState.value = UpdateState(context.getString(R.string.update_checking), busy = true)
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchRelease() }
                mutableState.value = UpdateState(
                    status = if (release == null) context.getString(R.string.update_latest)
                    else context.getString(R.string.update_available, release.version),
                    release = release,
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
        }
    }

    fun download(release: ReleaseInfo) {
        if (state.value.busy || saved.getLong(DOWNLOAD_ID, -1) != -1L) return
        mutableState.value = UpdateState(context.getString(R.string.update_download_preparing), busy = true)
        scope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val directory = updateDirectory()
                    if (!directory.exists() && !directory.mkdirs()) throw IOException(context.getString(R.string.storage_error))
                    // A unique file avoids DownloadManager's ERROR_FILE_ALREADY_EXISTS after retries.
                    val fileName = "personal-toolbox-${System.currentTimeMillis()}.apk"
                    val target = File(directory, fileName)
                    val downloadTarget = resolveDownloadUrl(release)
                    val request = DownloadManager.Request(Uri.parse(downloadTarget.url))
                        .setTitle("Toolbox v${release.version}")
                        .setDescription(context.getString(R.string.update_downloading))
                        .setMimeType(APK_MIME_TYPE)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(Uri.fromFile(target))
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                    if (downloadTarget.authenticated) {
                        request.addRequestHeader("Accept", "application/octet-stream")
                        request.addRequestHeader("Authorization", "Bearer ${UpdateConfig.GITHUB_TOKEN}")
                        request.addRequestHeader("User-Agent", "PersonalToolbox/${BuildConfig.VERSION_NAME}")
                    }
                    val downloadId = manager.enqueue(request)
                    // Persist before the completion event is processed on the main dispatcher.
                    val persisted = saved.edit().putLong(DOWNLOAD_ID, downloadId).putString(FILE_NAME, fileName)
                        .putInt(SOURCE_VERSION_CODE, BuildConfig.VERSION_CODE)
                        .putString(EXPECTED_SHA, release.sha256).putBoolean(VERIFIED, false)
                        .putBoolean(INSTALL_ATTEMPTED, false).putBoolean(AWAITING_PERMISSION, false).commit()
                    if (!persisted) {
                        manager.remove(downloadId)
                        saved.edit().clear().apply()
                        throw IOException("無法儲存更新下載記錄。")
                    }
                    downloadId
                }
                mutableState.value = UpdateState(context.getString(R.string.update_downloading), busy = true)
                onDownloadComplete(id) // Also handles a very fast completion before state was persisted.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
        }
    }

    fun onActivityResumed(activity: Activity) {
        foreground = WeakReference(activity)
        if (saved.getInt(SOURCE_VERSION_CODE, BuildConfig.VERSION_CODE) < BuildConfig.VERSION_CODE) {
            val oldId = saved.getLong(DOWNLOAD_ID, -1)
            if (oldId != -1L) manager.remove(oldId)
            saved.edit().clear().apply()
            checkForUpdates()
        }
        val id = saved.getLong(DOWNLOAD_ID, -1)
        if (id != -1L) onDownloadComplete(id)
    }

    fun onActivityPaused(activity: Activity) {
        if (foreground.get() === activity) foreground.clear()
    }

    /** Called by manifest receiver and on resume; ignores unrelated/spoofed IDs and queries real status. */
    fun onDownloadComplete(id: Long, onFinished: () -> Unit = {}) {
        if (id == -1L || id != saved.getLong(DOWNLOAD_ID, -1) || processingDownload) {
            onFinished()
            return
        }
        processingDownload = true
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if (!cursor.moveToFirst()) throw IOException("下載記錄不存在，請重新檢查更新。")
                        val result = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (result == DownloadManager.STATUS_FAILED) {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw IOException("下載失敗（代碼 $reason），請重新檢查更新。")
                        }
                        result
                    } ?: throw IOException("無法讀取系統下載狀態。")
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (!saved.getBoolean(VERIFIED, false)) {
                        withContext(Dispatchers.IO) { verifyApk(pendingFile()) }
                        saved.edit().putBoolean(VERIFIED, true).apply()
                    }
                    mutableState.value = UpdateState(context.getString(R.string.update_ready), installReady = true)
                    val activity = foreground.get()
                    if (activity != null && !activity.isFinishing &&
                        !saved.getBoolean(INSTALL_ATTEMPTED, false) &&
                        (!saved.getBoolean(AWAITING_PERMISSION, false) || context.packageManager.canRequestPackageInstalls())) {
                        install(activity)
                    }
                } else {
                    mutableState.value = UpdateState(context.getString(R.string.update_downloading), busy = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Failed/obsolete downloads must not permanently lock the update button.
                withContext(Dispatchers.IO) { manager.remove(id) }
                saved.edit().clear().apply()
                reportError(e)
            } finally {
                processingDownload = false
                onFinished()
            }
        }
    }

    fun install(activity: Activity) {
        try {
            if (!saved.getBoolean(VERIFIED, false) || !pendingFile().isFile) {
                throw IOException("更新檔案不存在或尚未驗證，請重新下載。")
            }
            if (!context.packageManager.canRequestPackageInstalls()) {
                saved.edit().putBoolean(AWAITING_PERMISSION, true).apply()
                mutableState.value = UpdateState(context.getString(R.string.update_permission_required), installReady = true)
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pendingFile())
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Toolbox APK", uri)
            }
            saved.edit().putBoolean(INSTALL_ATTEMPTED, true).putBoolean(AWAITING_PERMISSION, false).apply()
            activity.startActivity(intent)
            mutableState.value = UpdateState(context.getString(R.string.update_installing), installReady = true)
        } catch (e: Exception) {
            mutableState.value = UpdateState(context.getString(R.string.update_error, e.localizedMessage), installReady = true)
        }
    }

    private fun fetchRelease(): ReleaseInfo? {
        require(UpdateConfig.GITHUB_OWNER.matches(Regex("[A-Za-z0-9-]+"))) { "GitHub owner 格式不正確。" }
        require(UpdateConfig.GITHUB_REPO.matches(Regex("[A-Za-z0-9_.-]+"))) { "GitHub repository 格式不正確。" }
        val endpoint = "$API_BASE/repos/${UpdateConfig.GITHUB_OWNER}/${UpdateConfig.GITHUB_REPO}/releases/latest"
        val connection = apiConnection(endpoint, "application/vnd.github+json")
        val json = try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub HTTP $code；請確認倉庫、Release 與 Token 權限。")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        val tag = json.getString("tag_name").removePrefix("v")
        val latest = SemanticVersion.parse(tag) ?: throw IOException("Release tag 不是有效的語意化版本。")
        val current = SemanticVersion.parse(BuildConfig.VERSION_NAME) ?: throw IOException("本機版本格式不正確。")
        if (latest <= current) return null
        val assets = json.getJSONArray("assets")
        val apkAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val apk = apkAssets.firstOrNull { it.optString("name") == "app-release.apk" }
            ?: apkAssets.firstOrNull() ?: throw IOException("Release 沒有 APK 附件。")
        val browserUrl = apk.getString("browser_download_url")
        requireHttps(browserUrl)
        val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        return ReleaseInfo(tag, json.optString("body").ifBlank { context.getString(R.string.update_no_notes) },
            browserUrl, apk.getString("url"), digest)
    }

    private fun resolveDownloadUrl(release: ReleaseInfo): DownloadTarget {
        if (UpdateConfig.GITHUB_TOKEN.isBlank()) return DownloadTarget(requireHttps(release.browserDownloadUrl))
        // Private assets require the assets API; browser_download_url alone returns 404.
        val prefix = "$API_BASE/repos/${UpdateConfig.GITHUB_OWNER}/${UpdateConfig.GITHUB_REPO}/releases/assets/"
        require(release.assetApiUrl.startsWith(prefix)) { "非預期的 GitHub asset URL。" }
        val connection = apiConnection(release.assetApiUrl, "application/octet-stream")
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                // GitHub also permits a direct 200 binary response; authenticate only its exact assets API.
                return DownloadTarget(release.assetApiUrl, authenticated = true)
            }
            if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) {
                throw IOException("GitHub 未提供可供 DownloadManager 使用的下載轉址（HTTP ${connection.responseCode}）。")
            }
            // Pass only the short-lived signed URL to DownloadManager, never the PAT to a CDN.
            val location = connection.getHeaderField("Location") ?: throw IOException("下載轉址缺少 Location。")
            DownloadTarget(requireHttps(URL(URL(release.assetApiUrl), location).toString()))
        } finally { connection.disconnect() }
    }

    private fun apiConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "PersonalToolbox/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (UpdateConfig.GITHUB_TOKEN.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${UpdateConfig.GITHUB_TOKEN}")
            }
        }

    private fun requireHttps(url: String): String {
        require(URL(url).protocol == "https") { "更新來源必須使用 HTTPS。" }
        return url
    }

    private fun updateDirectory(): File = File(
        context.externalCacheDir ?: throw IOException(context.getString(R.string.storage_error)), "updates")

    private fun pendingFile(): File {
        val directory = updateDirectory().canonicalFile
        val name = saved.getString(FILE_NAME, null) ?: throw IOException("下載檔案記錄不存在。")
        val file = File(directory, name).canonicalFile
        require(file.parentFile == directory && name.endsWith(".apk")) { "不合法的更新檔案路徑。" }
        return file
    }

    private fun verifyApk(file: File) {
        if (!file.isFile || file.length() == 0L) throw IOException("下載檔案不存在或為空。")
        saved.getString(EXPECTED_SHA, null)?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(expected, ignoreCase = true)) throw IOException("APK SHA-256 驗證失敗。")
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IOException("下載內容不是有效的 APK。")
        if (archive.packageName != context.packageName) throw IOException("APK package name 不符。")
        if (archive.versionCode <= BuildConfig.VERSION_CODE) throw IOException("新版 APK 的 versionCode 必須大於目前版本。")
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val currentSignatures = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val nextSignatures = archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        if (currentSignatures.isEmpty() || currentSignatures != nextSignatures) {
            throw IOException("APK 簽章不同；請使用相同 release.jks，Debug 版無法直接覆蓋為 Release 版。")
        }
    }

    private fun reportError(e: Exception) {
        mutableState.value = UpdateState(context.getString(R.string.update_error, e.localizedMessage ?: e.javaClass.simpleName))
    }

    companion object {
        private const val API_BASE = "https://api.github.com"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DOWNLOAD_ID = "download_id"
        private const val FILE_NAME = "file_name"
        private const val EXPECTED_SHA = "expected_sha256"
        private const val VERIFIED = "verified"
        private const val INSTALL_ATTEMPTED = "install_attempted"
        private const val AWAITING_PERMISSION = "awaiting_permission"
        private const val SOURCE_VERSION_CODE = "source_version_code"
    }
}
