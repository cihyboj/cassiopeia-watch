package com.beamburst.casswatch.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.beamburst.casswatch.data.repository.GShockRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.NotificationType
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber

data class NotificationInfo(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val shortText: String,
    val type: NotificationType,
    val timestamp: Long
)

class NotificationMonitorService : NotificationListenerService() {
    private data class ServiceState(
        val isRunning: Boolean = false,
        val eventActions: List<EventAction> = emptyList()
    )

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationMonitorEntryPoint {
        fun repository(): GShockRepository
    }

    private val _state = MutableStateFlow(ServiceState())
    private val state: StateFlow<ServiceState> = _state.asStateFlow()

    // Per-instance id: the system recreates this service (startService toggles the
    // component), and a shared constant id would let the OLD instance's onDestroy tear
    // down the NEW instance's subscription.
    private val subscriberId = "$EVENT_SUBSCRIBER_ID-${System.identityHashCode(this)}"

    /** postTime of the last forwarded notification per key, to debounce re-posts. */
    private val lastForwardedAt = HashMap<String, Long>()

    init {
        initializeEventActions()
    }

    private fun initializeEventActions() {
        val actions = listOf(
            EventAction("WatchInitializationCompleted") { updateRunningState(true) },
            EventAction("Disconnect") { updateRunningState(false) }
        )
        _state.value = _state.value.copy(eventActions = actions)
        subscribeToEvents()
    }

    private fun subscribeToEvents() {
        ProgressEvents.runEventActions(subscriberId, state.value.eventActions.toTypedArray())
    }

    private fun updateRunningState(running: Boolean) {
        _state.value = _state.value.copy(isRunning = running)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.i("Notification listener service connected")
        // The system can recreate this listener while the watch is already connected;
        // waiting for the next WatchInitializationCompleted would silently drop
        // notifications until the next reconnect.
        runCatching {
            val repository = EntryPointAccessors.fromApplication(
                applicationContext,
                NotificationMonitorEntryPoint::class.java,
            ).repository()
            if (repository.isConnected() && repository.supportsAppNotifications()) {
                updateRunningState(true)
            }
        }.onFailure { Timber.w(it, "Could not query connection state on listener connect") }
    }

    override fun onDestroy() {
        ProgressEvents.subscriber.stop(subscriberId)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!state.value.isRunning) return
        if (!shouldForward(sbn)) return

        processNotification(sbn)
            .onSuccess { notificationInfo ->
                Timber.i("Notification received: $notificationInfo")
                createAppNotification(notificationInfo)
                    .let { appNotification ->
                        ProgressEvents.onNext("AppNotification", appNotification)
                    }
            }
            .onFailure { error ->
                Timber.e(error, "Error processing notification")
            }
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        // Never forward our own notifications (find-phone, foreground service, sync info) —
        // each forward is a BLE write and would echo back through this listener.
        if (sbn.packageName == packageName) return false
        // Ongoing/persistent notifications (media players, navigation, downloads) re-post
        // continuously and aren't discrete events worth buzzing the watch for.
        if (sbn.isOngoing) return false
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        // Debounce updates to the same notification key.
        val now = sbn.postTime
        val last = lastForwardedAt[sbn.key]
        if (last != null && now - last < FORWARD_DEBOUNCE_MS) return false
        if (lastForwardedAt.size > 200) lastForwardedAt.clear()
        lastForwardedAt[sbn.key] = now
        return true
    }

    private fun processNotification(sbn: StatusBarNotification): Result<NotificationInfo> =
        runCatching {
            val notification = sbn.notification
            val extras = notification.extras

            NotificationInfo(
                packageName = sbn.packageName,
                appName = getAppName(sbn.packageName),
                title = extras.getString(Notification.EXTRA_TITLE).orEmpty(),
                text = extras.getString(Notification.EXTRA_TEXT).orEmpty(),
                shortText = extras.getString(Notification.EXTRA_SUB_TEXT).orEmpty(),
                type = classifyNotificationType(sbn.packageName, notification),
                timestamp = sbn.postTime
            )
        }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private val knownAppCategories = mapOf(
        "com.google.android.calendar" to NotificationType.CALENDAR,
        "com.google.android.gm" to NotificationType.EMAIL,
        "com.whatsapp" to NotificationType.MESSAGE,
        "com.google.android.apps.messaging" to NotificationType.MESSAGE,
        "com.facebook.messenger" to NotificationType.MESSAGE,
        "com.facebook.katana" to NotificationType.GENERIC,      // Social
        "com.twitter.android" to NotificationType.GENERIC,      // Social
        "com.instagram.android" to NotificationType.GENERIC,    // Social
        "com.linkedin.android" to NotificationType.GENERIC      // Social
    )

    private fun classifyNotificationType(
        packageName: String,
        notification: Notification
    ): NotificationType {
        // First check known apps
        knownAppCategories[packageName]?.let { return it }

        // Then check notification category
        return when (notification.category) {
            Notification.CATEGORY_EMAIL -> NotificationType.EMAIL
            Notification.CATEGORY_MESSAGE -> NotificationType.MESSAGE
            Notification.CATEGORY_EVENT -> NotificationType.CALENDAR
            Notification.CATEGORY_SOCIAL -> NotificationType.GENERIC
            Notification.CATEGORY_CALL -> NotificationType.PHONE_CALL
            else -> NotificationType.GENERIC
        }
    }

    companion object {
        private const val EVENT_SUBSCRIBER_ID = "NotificationMonitorService"
        private const val FORWARD_DEBOUNCE_MS = 10_000L

        private fun isNotificationGranted(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat?.contains(packageName) == true
        }

        private fun toggleNotificationListenerService(context: Context) {
            val pm = context.packageManager
            val componentName = ComponentName(context, NotificationMonitorService::class.java)
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        fun startService(context: Context) {
            if (!isNotificationGranted(context)) {
                openNotificationListenerSettings(context)
            } else {
                // Force reconnect the service...
                // val toggleIntent = Intent(context, NotificationMonitorService::class.java)
                // context.startService(toggleIntent)

                // Toggling is more reliable than restarting the service
                toggleNotificationListenerService(context)
            }
        }

        private fun openNotificationListenerSettings(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun createAppNotification(info: NotificationInfo): AppNotification {
            fun Long.toFormattedDateTime(): String {
                val calendar = java.util.Calendar.getInstance().apply {
                    timeInMillis = this@toFormattedDateTime
                }

                return String.format(
                    java.util.Locale.US,
                    "%04d%02d%02dT%02d%02d%02d",
                    calendar.get(java.util.Calendar.YEAR),
                    calendar.get(java.util.Calendar.MONTH) + 1,
                    calendar.get(java.util.Calendar.DAY_OF_MONTH),
                    calendar.get(java.util.Calendar.HOUR_OF_DAY),
                    calendar.get(java.util.Calendar.MINUTE),
                    calendar.get(java.util.Calendar.SECOND)
                )
            }

            return AppNotification(
                title = info.title,
                text = info.text,
                shortText = info.shortText,
                app = info.appName,
                type = info.type,
                timestamp = info.timestamp.toFormattedDateTime()
            )
        }
    }
}
