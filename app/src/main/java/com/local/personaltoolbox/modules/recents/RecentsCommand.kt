@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.modules.recents

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.Looper
import com.local.personaltoolbox.BuildConfig

/** Loaded from our signed APK by app_process through the user's local, paired ADB session.
 * No socket, exported service, arbitrary shell interface or persistent privileged daemon. */
object RecentsCommand {
    @JvmStatic fun main(args: Array<String>) {
        val nonce = args.lastOrNull() ?: return
        if (!RecentsProtocol.validNonce(nonce)) return
        val status = try { execute(args) } catch (error: Throwable) {
            if (BuildConfig.DEBUG) {
                var cause: Throwable? = error
                repeat(4) {
                    cause?.let { System.err.println("Recents helper: ${it.javaClass.simpleName}: ${it.message}") }
                    cause = cause?.cause
                }
            }
            "FAILED"
        }
        println(RecentsProtocol.reply(nonce, status))
        System.out.flush()
        kotlin.system.exitProcess(0)
    }

    private fun execute(args: Array<String>): String {
        require(Process.myUid() == 2000 || Process.myUid() == 0)
        require(args.size == 6)
        val operation = args[0]
        val target = args[1]
        val user = args[2].toInt()
        val foregroundOnly = when (args[3]) { "1" -> true; "0" -> false; else -> error("mode") }
        val expiresAt = args[4].toLong()
        require(expiresAt - SystemClock.elapsedRealtime() in 0..5_000)
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        val context = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        for (permission in listOf("android.permission.REMOVE_TASKS", "android.permission.REAL_GET_TASKS")) {
            require(context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        val currentUser = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        require(user == currentUser)
        if (operation == "probe") {
            cards(manager) // Verify access on this Android build before reporting readiness.
            taskService().javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            return "READY"
        }
        require(operation == "remove" && target in RecentsTargets.packages)
        val service = taskService()
        val method = service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).apply { isAccessible = true }
        return RecentsRemoval(object : RecentsRemoval.Host {
            override fun cards() = cards(manager)
            override fun interactiveAndUnlocked() = context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            override fun foreground() = manager.getRunningTasks(1).singleOrNull()?.let { it.id to it.topActivity?.packageName }
            override fun now() = SystemClock.elapsedRealtime()
            override fun remove(taskId: Int) = method.invoke(service, taskId) == true
        }).run(target, user, foregroundOnly, expiresAt).name
    }

    private fun taskService(): Any = Class.forName("android.app.ActivityTaskManager")
        .getMethod("getService").invoke(null)!!

    private fun cards(manager: ActivityManager): List<RecentCard> = manager.getRecentTasks(100, 0).map { task ->
        RecentCard(task.persistentId, task.javaClass.getField("userId").getInt(task),
            task.baseIntent.component?.packageName ?: task.baseIntent.`package`,
            (task.javaClass.getField("realActivity").get(task) as? ComponentName)?.packageName,
            task.topActivity?.packageName, task.javaClass.getField("lastActiveTime").getLong(task))
    }
}
