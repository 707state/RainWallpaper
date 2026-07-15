package com.jask.rainwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.jask.rainwallpaper.effect.Effects
import com.jask.rainwallpaper.effect.GLEffect
import com.jask.rainwallpaper.gl.GLWallpaperEngine
import java.io.File

class RainWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ConfigurableEngine()

    /**
     * A single engine handles both pass-through rendering and effects. Keeping the
     * engine type stable lets an already active live wallpaper reload its image
     * and effect without relying on Android to recreate the WallpaperService.
     */
    private inner class ConfigurableEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val preferences by lazy {
            getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        }

        private var glEngine: GLWallpaperEngine? = null
        private var bitmap: Bitmap? = null
        private var effect: GLEffect? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var surfaceAvailable = false
        private var visible = false

        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null

        private val accelerometerListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                glEngine?.tiltAx = -event.values[0]
                glEngine?.tiltAy = event.values[1]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            preferences.registerOnSharedPreferenceChangeListener(this)
            loadConfiguration()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            surfaceAvailable = true

            glEngine?.surfaceChanged(width, height) ?: startGL()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (visible) {
                glEngine?.resume()
                registerSensorIfNeeded()
            } else {
                unregisterSensor()
                glEngine?.pause()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceAvailable = false
            stopGL()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            preferences.unregisterOnSharedPreferenceChangeListener(this)
            stopGL()
            recycleBitmap()
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            // A save updates both the effect and revision in one editor. Listen
            // only to the revision marker so one user action rebuilds GL once.
            if (key == CONFIG_REVISION_KEY) {
                reloadConfiguration()
            }
        }

        private fun reloadConfiguration() {
            // Preference callbacks are delivered on the writer's thread. In this
            // app they originate from the main thread, matching Engine callbacks.
            stopGL()
            recycleBitmap()
            loadConfiguration()
            if (surfaceAvailable) {
                startGL()
            }
        }

        private fun loadConfiguration() {
            effect = Effects.fromKey(preferences.getString(EFFECT_MODE_KEY, "none") ?: "none")
            bitmap = decodeWallpaperBitmap()
        }

        private fun decodeWallpaperBitmap(): Bitmap? {
            val file = File(filesDir, WALLPAPER_FILE_NAME)
            return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }

        private fun recycleBitmap() {
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            bitmap = null
        }

        private fun startGL() {
            if (!surfaceAvailable || surfaceWidth <= 0 || surfaceHeight <= 0) return

            val metrics = resources.displayMetrics
            val heightCm = surfaceHeight / (metrics.ydpi / 2.54f)
            val safeHeightCm = heightCm.coerceAtLeast(1f)
            val dropRadiusUV = DROP_RADIUS_CM / safeHeightCm

            glEngine = GLWallpaperEngine(
                holder = surfaceHolder,
                bitmap = bitmap,
                config = GLWallpaperEngine.Config(
                    effect = effect,
                    dropRadiusUV = dropRadiusUV,
                    screenHeightCm = safeHeightCm
                )
            ).also {
                it.start(surfaceWidth, surfaceHeight)
                if (!visible) it.pause()
            }
            registerSensorIfNeeded()
        }

        private fun stopGL() {
            unregisterSensor()
            glEngine?.stop()
            glEngine = null
        }

        private fun registerSensorIfNeeded() {
            if (!visible || effect?.usesSensors != true) return
            sensorManager?.registerListener(
                accelerometerListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(accelerometerListener)
        }
    }

    companion object {
        const val PREFERENCES_NAME = "wallpaper_settings"
        const val EFFECT_MODE_KEY = "effect_mode"
        const val CONFIG_REVISION_KEY = "config_revision"
        const val WALLPAPER_FILE_NAME = "wallpaper_image.jpg"

        private const val DROP_RADIUS_CM = 0.25f
    }
}
