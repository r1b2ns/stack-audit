package io.github.r1b2ns.stackaudit.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.startup.Initializer
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StackAuditInitializer
import kotlin.math.sqrt

public object StackAuditUi {
    @Volatile
    internal var context: Context? = null

    public var shakeToOpen: Boolean = true

    public fun install(context: Context) {
        val app = context.applicationContext
        if (this.context != null) return
        this.context = app
        StackAudit.registerViewer {
            app.startActivity(Intent(app, StackAuditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        (app as? Application)?.registerActivityLifecycleCallbacks(ShakeWatcher(app))
    }
}

public class StackAuditUiInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        StackAuditUi.install(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(StackAuditInitializer::class.java)
}

internal class ShakeDetector(
    private val threshold: Float = 2.7f,
    private val cooldownMs: Long = 1500,
    private val onShake: () -> Unit,
) {
    private var last = 0L

    fun onAcceleration(
        x: Float,
        y: Float,
        z: Float,
        now: Long,
    ) {
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g > threshold && now - last > cooldownMs) {
            last = now
            onShake()
        }
    }
}

private class ShakeWatcher(
    context: Context,
) : Application.ActivityLifecycleCallbacks,
    SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val detector = ShakeDetector { StackAudit.show() }
    private var resumed: Activity? = null

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
        if (!StackAuditUi.shakeToOpen || activity is StackAuditActivity) return
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        detector.onAcceleration(event.values[0], event.values[1], event.values[2], System.currentTimeMillis())
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
