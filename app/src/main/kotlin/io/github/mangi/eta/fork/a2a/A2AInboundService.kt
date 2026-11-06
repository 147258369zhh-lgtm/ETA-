package io.github.mangi.eta.fork.a2a

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

/** Foreground owner for the private inbound A2A listener. */
internal class A2AInboundService : Service() {
    private var engine: A2AInboundEngine? = null
    private lateinit var configStore: A2AInboundConfigStore

    override fun onCreate() {
        super.onCreate()
        configStore = A2AInboundConfigStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "A2A incoming endpoint", NotificationManager.IMPORTANCE_LOW),
        )
        startForegroundCompat("Starting private A2A endpoint")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            configStore.setEnabled(false)
            stopListener()
            return START_NOT_STICKY
        }
        if (!configStore.snapshot().enabled) {
            configStore.setLastError(null)
            stopSelf()
            return START_NOT_STICKY
        }
        startListenerIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListener()
        super.onDestroy()
    }

    private fun startListenerIfNeeded() {
        if (engine != null) {
            updateNotification("Listening on port ${configStore.snapshot().port}")
            return
        }
        val snapshot = configStore.snapshot()
        if (!snapshot.enabled) {
            stopSelf()
            return
        }
        if (snapshot.token.isBlank()) {
            configStore.setLastError("Unable to create the inbound bearer token")
            stopSelf()
            return
        }
        runCatching {
            A2AInboundEngine(
                context = this,
                port = snapshot.port,
                bearerTokenProvider = { configStore.loadToken().orEmpty() },
                bindAddress = snapshot.bindAddress,
                advertisedEndpointProvider = { configStore.snapshot().advertisedEndpointUrl },
            ).also { engine ->
                engine.start()
                this.engine = engine
            }
            configStore.setLastError(null)
            updateNotification("Listening for A2A requests on port ${snapshot.port}")
        }.onFailure { failure ->
            configStore.setLastError(failure.message ?: failure.javaClass.simpleName)
            updateNotification("A2A endpoint failed to start")
            stopSelf()
        }
    }

    private fun stopListener() {
        runCatching { engine?.stop() }
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, A2AInboundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Eta A2A endpoint")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "eta_a2a_inbound"
        private const val NOTIFICATION_ID = 1113
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_A2A_INBOUND"

        fun start(context: Context) {
            context.applicationContext.startForegroundService(Intent(context, A2AInboundService::class.java))
        }

        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context, A2AInboundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
