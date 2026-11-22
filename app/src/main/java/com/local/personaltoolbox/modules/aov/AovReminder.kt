package com.local.personaltoolbox.modules.aov

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.local.personaltoolbox.R
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.modules.recents.RecentApps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Instant

object AovReminder {
    const val ID = "aov_crystal"
    const val GAME_PACKAGE = RecentApps.AOV
    const val DATE_EXTRA = "reminder_date"
    const val CONFIRM_EXTRA = "confirm_from_notification"
    private const val ALARM_ACTION = "com.local.personaltoolbox.AOV_NOON"
    private const val CHANNEL = "aov_crystal_daily"
    private const val NOTIFICATION_ID = 300

    fun initialize(app: ToolboxApplication) {
        createChannel(app)
        app.applicationScope.launch {
            app.preferences.settings.filterNotNull().map { it.isEnabled(ID) }.distinctUntilChanged()
                .collect { schedule(app, it) }
        }
        app.applicationScope.launch {
            AovRunRequests.requests.collectLatest { request ->
                if (request != null) {
                    delay(30_000)
                    if (AovRunRequests.requests.value == request && !AovRunRequests.hasStarted(request)) {
                        AovReminderStore(app.preferences).releaseOpen(request.date)
                        AovRunRequests.finish(request, "遊戲未啟動或無障礙服務未就緒，未送出購買")
                    }
                }
            }
        }
    }

    private fun alarmIntent(context: Context, date: String) = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
        Intent(context, AovAlarmReceiver::class.java).setAction(ALARM_ACTION).putExtra(DATE_EXTRA, date),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun hasExactPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(context: Context, enabled: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val next = AovReminderTime.nextNoon(Instant.now())
        val pending = alarmIntent(context, AovReminderTime.today(next).toString())
        if (!enabled) {
            alarm.cancel(pending)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            return
        }
        val time = next.toEpochMilli()
        if (hasExactPermission(context)) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
                return
            } catch (_: SecurityException) { /* Permission may have changed after the check. */ }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
    }

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.aov_notification_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    @SuppressLint("MissingPermission")
    internal fun notify(context: Context) {
        createChannel(context)
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, AovReminderActivity::class.java)
                .putExtra(DATE_EXTRA, AovReminderTime.today().toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val confirm = PendingIntent.getActivity(context, NOTIFICATION_ID + 1,
            Intent(context, AovReminderActivity::class.java)
                .putExtra(DATE_EXTRA, AovReminderTime.today().toString())
                .putExtra(CONFIRM_EXTRA, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle(context.getString(R.string.aov_title))
            .setContentText(context.getString(R.string.aov_notification_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.aov_notification_body)))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.aov_confirm_open), confirm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
        catch (_: SecurityException) { /* Notifications were revoked while building the notification. */ }
    }

    fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    internal fun isAlarm(intent: Intent) = intent.action == ALARM_ACTION
}

class AovAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AovReminder.isAlarm(intent)) return
        runAsync(context) { app ->
            val enabled = app.preferences.settings.filterNotNull().first().isEnabled(AovReminder.ID)
            AovReminder.schedule(app, enabled)
            if (enabled && AovReminderTime.isCurrentDay(intent.getStringExtra(AovReminder.DATE_EXTRA)) &&
                !AovReminderStore(app.preferences).hasOpenedToday()) AovReminder.notify(app)
        }
    }
}

class AovRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        runAsync(context) { app ->
            AovReminder.schedule(app, app.preferences.settings.filterNotNull().first().isEnabled(AovReminder.ID))
        }
    }
}

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (ToolboxApplication) -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext as ToolboxApplication
    app.applicationScope.launch {
        try { withTimeout(8_000) { block(app) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e("AovReminder", "Unable to process reminder", e) }
        finally { pending.finish() }
    }
}
