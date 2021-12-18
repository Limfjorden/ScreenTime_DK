package de.markusfisch.android.screentime.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import de.markusfisch.android.screentime.R
import de.markusfisch.android.screentime.activity.MainActivity
import de.markusfisch.android.screentime.app.db
import de.markusfisch.android.screentime.data.Summary
import de.markusfisch.android.screentime.data.summarizeDay
import de.markusfisch.android.screentime.data.timeRangeColloquial
import de.markusfisch.android.screentime.notification.buildNotification
import de.markusfisch.android.screentime.receiver.*
import kotlinx.coroutines.*
import java.lang.Runnable

val screenReceiver = EventReceiver()

fun Context.startTrackerService(
	callback: ((intent: Intent) -> Any)? = null
) {
	val intent = Intent(this, TrackerService::class.java)
	callback?.let {
		callback(intent)
	}
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		startForegroundService(intent)
	} else {
		startService(intent)
	}
}

class TrackerService : Service() {
	private val job = SupervisorJob()
	private val scope = CoroutineScope(Dispatchers.Default + job)
	private val handler by lazy { Handler(mainLooper) }
	private val updateNotificationRunnable = Runnable {
		updateNotification()
	}

	private lateinit var notificationManager: NotificationManager
	private lateinit var powerManager: PowerManager

	private var summary: Summary? = null

	override fun onCreate() {
		super.onCreate()

		notificationManager = getSystemService(
			Context.NOTIFICATION_SERVICE
		) as NotificationManager
		powerManager = getSystemService(
			Context.POWER_SERVICE
		) as PowerManager

		val filter = IntentFilter()
		filter.addAction(Intent.ACTION_SCREEN_ON)
		filter.addAction(Intent.ACTION_SCREEN_OFF)
		filter.addAction(Intent.ACTION_USER_PRESENT)
		registerReceiver(screenReceiver, filter)

		scope.launch {
			val notification = buildAndScheduleNotification()
			withContext(Dispatchers.Main) {
				startForeground(ID, notification)
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		unregisterReceiver(screenReceiver)
		job.cancelChildren()
	}

	override fun onBind(intent: Intent): IBinder? {
		return null
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int
	): Int {
		if (intent?.hasExtra(UPDATE_NOTIFICATION) == true) {
			updateNotification()
		} else if (intent?.hasExtra(SCREEN_STATE) == true &&
			intent.hasExtra(TIMESTAMP)
		) {
			val screenOn = intent.getBooleanExtra(SCREEN_STATE, true)
			// Ignore ACTION_SCREEN_OFF events when the device is still
			// interactive (e.g. when the camera app was opened by double
			// pressing on/off there's a ACTION_SCREEN_OFF event directly
			// followed by ACTION_SCREEN_ON).
			if (!screenOn && isInteractive()) {
				return START_STICKY
			}
			insertScreenEvent(
				intent.getLongExtra(TIMESTAMP, System.currentTimeMillis()),
				screenOn,
				intent.getFloatExtra(BATTERY_LEVEL, 0f)
			)
		}
		return START_STICKY
	}

	private fun insertScreenEvent(
		timestamp: Long,
		screenOn: Boolean,
		battery: Float
	) {
		db.insertScreenEvent(timestamp, screenOn, battery)
		if (screenOn) {
			updateNotification()
		} else {
			cancelNotificationUpdate()
			summary = null
		}
	}

	private fun cancelNotificationUpdate() {
		handler.removeCallbacks(updateNotificationRunnable)
	}

	private fun scheduleNotificationUpdate(delay: Long) {
		cancelNotificationUpdate()
		if (isInteractive()) {
			handler.postDelayed(updateNotificationRunnable, delay)
		}
	}

	private fun isInteractive() = if (
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
	) {
		powerManager.isInteractive
	} else {
		@Suppress("DEPRECATION")
		powerManager.isScreenOn
	}

	private fun updateNotification() {
		scope.launch {
			val notification = buildAndScheduleNotification()
			withContext(Dispatchers.Main) {
				notificationManager.notify(ID, notification)
			}
		}
	}

	private fun Context.buildAndScheduleNotification(): Notification {
		val now = System.currentTimeMillis()
		val sum = summary ?: summarizeDay(now)
		val seconds = (sum.total + (if (sum.ongoingSince > -1) {
			now - sum.ongoingSince
		} else {
			0
		})) / 1000L
		scheduleNotificationUpdate(msToNextFullMinute(now))
		return buildNotification(
			R.drawable.ic_notify,
			timeRangeColloquial(seconds),
			Intent(this, MainActivity::class.java)
		)
	}

	companion object {
		const val ID = 1
	}
}

fun msToNextFullMinute(
	now: Long = System.currentTimeMillis()
): Long = 60000L - now % 60000L
