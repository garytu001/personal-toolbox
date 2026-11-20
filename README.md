# Toolbox

個人模組化工具箱。Kotlin + Android XML Views，無 Jetpack Compose。

正式更新來源：[garytu001/personal-toolbox](https://github.com/garytu001/personal-toolbox)。

手機直接下載：[最新正式 APK](https://github.com/garytu001/personal-toolbox/releases/latest/download/app-release.apk)。第一次由舊 Debug 轉正式版需卸載舊版再安裝（會清除舊資料）；往後使用 App 頂部「更新」原地安裝相同簽章版本，不需要傳輸線。

## 建置設定

| 項目 | 值 |
| --- | --- |
| applicationId | com.local.personaltoolbox |
| App 名稱 | Toolbox |
| min / target / compile SDK | 26 / 34 / 34 |
| versionCode / versionName | 5 / 1.1.0 |
| Gradle / AGP / Kotlin | 8.14.3 / 8.13.2 / 2.1.20 |
| JVM 目標 / CI JDK | 17 / 17 |

目前版本為 5 / 1.1.0；以後每次發布同時增加 versionCode 與語意版本。本版新增預設關閉的 YouTube 廣告略過模組，保留正式 GitHub 更新來源、純黑白灰配色、Shorts／Reels 播放頁退出；沒有遮罩。

Windows：

```powershell
.\setup_local.ps1
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=COMPAT'
```

macOS / Linux：

```bash
chmod +x gradlew setup_local.sh generate_keystore.sh
./setup_local.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

setup_local 腳本偵測 ANDROID_HOME、ANDROID_SDK_ROOT 與系統常見 SDK 路徑，建立 local.properties。需安裝 Android SDK Platform 34、Build-Tools 35.0.0，以及 JDK 17 或 21。

目前專案位於包含中文字的 Windows 路徑；gradle.properties 已依 AGP 的錯誤提示設定 android.overridePathCheck=true。此路徑的本機驗收使用 Android Studio JBR 21 與上述 COMPAT 參數，使 Gradle 測試載入器的路徑編碼符合 Windows 原生編碼；不需要修改手機或 Windows 全域設定。CI 仍用 Linux／JDK 17 與標準 Gradle 命令。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
正式簽署 APK：`app/build/outputs/apk/release/app-release.apk`。

## 模組容器

- 固定頂部：狀態點、無障礙狀態、版本，以及「無障礙」「電池」「更新」。
- 垂直 RecyclerView：全寬緊湊卡片、名稱、計數、Switch 與拖曳握把。
- 握把觸控或長按卡片可上下拖曳；放開即保存順序。主畫面無標語、教學或功能解說。
- 根佈局以 WindowInsets 處理狀態列、導覽列與螢幕切口；計數與版本使用 tnum 等寬數字。
- 純黑白灰配色：背景 #000000、卡片 #121212、1dp 邊框 #303030、主文字 #F5F5F5、次文字 #9E9E9E、強調 #FFFFFF；12dp 圓角、8dp 卡片間距、無陰影。啟用狀態使用白色，停用狀態使用灰色。

新增模組的註冊方式、資料儲存與發版規則，見根目錄 **AGENTS.md**。唯一註冊表為 `modules/ModuleRegistry.kt`，UI、預設資料與無障礙服務從同一張表產生，不在 Activity 或服務中硬編碼模組 ID 分支。

目前模組：`yt_shorts`、`ig_reels`、`yt_ad_skip`。服務以套件分派至獨立模組實例，每個模組維持 450ms 冷卻；Instagram 僅偵測 clips 與 Reels，排除 Stories 子樹，優先回首頁再後退。新模組會附加到使用者既有順序末尾。

## YouTube 廣告略過

此模組預設關閉。手機使用正式版 v1.0.3 時，可按「更新」取得 v1.1.0，保留開關、排序及計數；安裝後先在系統無障礙設定將 Toolbox 關閉再開啟一次，以載入新增的 canPerformGestures 能力，再開啟「YouTube 廣告略過」。沒有新增一般 uses-permission。

- 只處理 YouTube 內可見、已啟用、可按的略過廣告按鈕，優先比對 view ID，再以明確文字／內容描述與按鈕語意做保守匹配。倒數、Premium 推銷、隱藏或未啟用控制項不點擊；沒有略過按鈕的廣告不處理。
- 延遲使用均值 500ms、標準差 200ms 的常態分佈，拒絕重抽超出 0–1000ms 的樣本；不以邊界裁切代替截斷取樣。
- 點擊 X/Y 各自以按鈕中心為均值、中央安全區寬／高的 1/6 為標準差取樣，截斷在按鈕中央 60% 矩形內；不使用固定螢幕座標。
- 等待後重新取得按鈕，核對套件、視窗、識別資訊及範圍完全相同才點擊。畫面或按鈕改變、操作畫面、關閉模組或停止服務時取消待執行工作。
- 每次可用按鈕最多嘗試一次。40ms 點擊手勢完成後，待介面更新並確認略過按鈕消失才累計；手勢失敗、取消、超時或按鈕仍在都不計數。
- Android 的程式手勢可能中斷當時的觸控操作。已取消可觀察到的手動點擊／捲動期間等待中的動作，但不能保證辨識所有觸控情況。
- 隨機延遲與位置不保證防偵測、符合 YouTube 條款或帳號零風險。第三方 App 改版可能令識別失效；這是可關閉的個人實驗功能，需要實機測試。

遮罩與底部入口觸控攔截、視窗檢索能力及背景輪詢均已移除；首頁縮圖與入口由原 App 顯示。保留 Shorts／Reels 播放頁的返回／切回首頁，仍有各自 450ms 冷卻。只監聽指定套件的視窗狀態、內容變更，以及用於取消等待工作的點擊／捲動事件，並核對目前視窗套件以防過期事件誤操作。

## 本機資料

共用 `toolbox_preferences` Preferences DataStore：

| 鍵 | 型別 | 預設 |
| --- | --- | --- |
| yt_shorts_enabled | Boolean | true |
| yt_shorts_blocked_count | Int | 0 |
| ig_reels_enabled | Boolean | true |
| ig_reels_blocked_count | Int | 0 |
| yt_ad_skip_enabled | Boolean | false |
| yt_ad_skip_blocked_count | Int | 0 |
| module_order | String | yt_shorts,ig_reels |

舊 youtube_shorts_* / instagram_reels_* 經 DataMigration 遷移至新 ID，不覆蓋已存在的新鍵。新增模組附加到已存順序末尾，未知或重複 ID 不產生空卡片。計數採原子更新，防止溢位。

## GitHub 更新與簽章

1. `UpdateConfig.kt` 已設定 GITHUB_OWNER=garytu001、GITHUB_REPO=personal-toolbox、GITHUB_TOKEN 為空。公開更新下載不需要手機保存 Token。
2. 本機已生成正式 release.jks、keystore.properties，後續所有版本必須沿用，不可重新產生或更換。請離線備份金鑰與密碼；腳本拒絕覆寫既有金鑰。
3. GitHub 倉庫 Settings → Secrets and variables → Actions，建立：
   - KEYSTORE_BASE64
   - KEYSTORE_PASSWORD
   - KEY_ALIAS
   - KEY_PASSWORD
4. 推送與 versionName 相同的 Tag，例如 `v1.1.0`。Workflow 使用 JDK 17，還原金鑰、編譯簽署、測試及 lint，透過 softprops/action-gh-release@v2 建立 Release 並上傳 app-release.apk。
5. 下一版例如 versionCode 6、versionName 1.1.1，再推送 v1.1.1。永遠使用相同 applicationId 與 Release 金鑰。

正式簽章憑證 SHA-256：`ecd1392a6cb1e6aa747fe65c75bfcc862ace087cd8700c06fb0406d10de81daf`。

Release 建置各欄位優先讀 KEYSTORE_PATH、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD 環境變數，缺少時讀 keystore.properties。未配置簽章時 Debug 可建置，Release 明確失敗。KEYSTORE_PATH 不用存進 GitHub Secrets，workflow 自行設定 Runner 暫存路徑。

首次正式使用請安裝自己金鑰簽署的 Release 版；Debug 不能直接覆蓋成不同簽章的 Release 版。相同簽章的原地更新保留本機開關、順序與計數，系統仍可能要求重新確認部分特殊權限。

OtaUpdater 使用 HttpURLConnection / org.json 比對 SemVer，DownloadManager 下載到 App 專屬外部快取，完成後檢查 APK 套件、版本碼、簽章及可用的 SHA-256，再透過 FileProvider 啟動系統安裝。支援私人附件與未知來源授權；背景下載完成後返回 App 繼續安裝。主畫面「更新」按鈕也可繼續已下載的安裝。

Manifest 只包含 INTERNET、REQUEST_INSTALL_PACKAGES 兩項 uses-permission；BIND_ACCESSIBILITY_SERVICE 配置在 service 的綁定保護權限。沒有儲存或 SYSTEM_ALERT_WINDOW 權限。FileProvider authority 為 `${applicationId}.fileprovider`。

## 驗收

- `assembleDebug`、`testDebugUnitTest`、`lintDebug`。
- 35 項 JVM 測試：SemVer 5、排序規則 5、DataStore 4、廣告按鈕規則 5、截斷常態取樣 4、延遲／取消／重驗證／去重／完成確認 12。
- 實機仍需核對：狀態列與底部內距、拖曳排序、開關／計數保留、Shorts／Reels、Stories、廣告按鈕辨識、手勢落點與成功次數，以及正式 GitHub OTA。
- GitHub 正式倉庫為 garytu001/personal-toolbox；發版結果見 Releases 與 Actions。完整手機端安裝／更新仍需實機確認。

參考：[ItemTouchHelper](https://developer.android.com/reference/androidx/recyclerview/widget/ItemTouchHelper)、[WindowInsets](https://developer.android.com/develop/ui/views/layout/edge-to-edge)、[GitHub Release assets](https://docs.github.com/en/rest/releases/assets)。
