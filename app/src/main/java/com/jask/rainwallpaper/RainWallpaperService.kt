package com.jask.rainwallpaper

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.jask.rainwallpaper.gl.GLWallpaperEngine
import com.jask.rainwallpaper.effect.Effects
import com.jask.rainwallpaper.effect.GLEffect
import java.io.File

class RainWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        val prefs = getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
        val effect = prefs.getString("effect_mode", "none") ?: "none"
        val glEffect = Effects.fromKey(effect)
        return if (glEffect != null) GLEngine(glEffect) else ImageEngine()
    }

    // ─── Canvas-based engine (no effect / fallback) ──────────────────────────

    private inner class ImageEngine : Engine() {
        private var bitmap: Bitmap? = null

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            loadBitmap()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            drawWallpaper()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) drawWallpaper()
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xStep: Float, yStep: Float,
            xPixels: Int, yPixels: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels)
            drawWallpaper()
        }

        private fun loadBitmap() {
            val file = File(filesDir, "wallpaper_image.jpg")
            if (file.exists()) {
                bitmap = BitmapFactory.decodeFile(file.absolutePath)
            }
        }

        private fun drawWallpaper() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return

                val bmp = bitmap
                if (bmp != null && !bmp.isRecycled) {
                    val cw = canvas.width.toFloat()
                    val ch = canvas.height.toFloat()
                    val sx = cw / bmp.width.toFloat()
                    val sy = ch / bmp.height.toFloat()
                    val s = maxOf(sx, sy)
                    val sw = bmp.width * s
                    val sh = bmp.height * s
                    val l = (cw - sw) / 2f
                    val t = (ch - sh) / 2f
                    canvas.drawBitmap(bmp, null, RectF(l, t, l + sw, t + sh), Paint(Paint.FILTER_BITMAP_FLAG))
                } else {
                    canvas.drawColor(Color.parseColor("#1a1a2e"))
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }
    }

    // ─── GL engine (effect-driven) ───────────────────────────────────────────

    private inner class GLEngine(private val glEffect: GLEffect) : Engine() {
        private var glEngine: GLWallpaperEngine? = null
        private var bitmap: Bitmap? = null
        private var ready = false
        private var pendingW = 0
        private var pendingH = 0

        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        @Volatile private var tiltAx = 0f
        @Volatile private var tiltAy = 0f

        private val accelerometerListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                tiltAx = -event.values[0]
                tiltAy = event.values[1]
                glEngine?.tiltAx = tiltAx
                glEngine?.tiltAy = tiltAy
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            loadBitmap()
            ready = true
            if (pendingW > 0 && pendingH > 0) {
                startGL(pendingW, pendingH)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            if (!ready) {
                pendingW = width
                pendingH = height
                return
            }
            glEngine?.let {
                it.surfaceChanged(width, height)
            } ?: run {
                startGL(width, height)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glEngine?.resume()
            } else {
                glEngine?.pause()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            stopGL()
        }

        override fun onDestroy() {
            super.onDestroy()
            stopGL()
        }

        private fun startGL(w: Int, h: Int) {
            stopGL()
            val metrics = resources.displayMetrics
            val heightCm = h / (metrics.ydpi / 2.54f)
            val radiusCm = 0.25f
            val dropRadiusUV = radiusCm / heightCm
            glEngine = GLWallpaperEngine(
                holder = surfaceHolder,
                bitmap = bitmap,
                config = GLWallpaperEngine.Config(
                    effect = glEffect,
                    dropRadiusUV = dropRadiusUV,
                    screenHeightCm = heightCm
                )
            )
            glEngine!!.start(w, h)
            sensorManager?.registerListener(
                accelerometerListener, accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        private fun stopGL() {
            sensorManager?.unregisterListener(accelerometerListener)
            glEngine?.stop()
            glEngine = null
        }

        private fun loadBitmap() {
            val file = File(filesDir, "wallpaper_image.jpg")
            if (file.exists()) {
                bitmap = BitmapFactory.decodeFile(file.absolutePath)
            }
        }
    }
}
