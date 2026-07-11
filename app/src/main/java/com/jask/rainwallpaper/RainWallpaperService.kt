package com.jask.rainwallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class RainWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ImageEngine()

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
            if (visible) {
                drawWallpaper()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int
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
                    val canvasW = canvas.width.toFloat()
                    val canvasH = canvas.height.toFloat()
                    val scaleX = canvasW / bmp.width.toFloat()
                    val scaleY = canvasH / bmp.height.toFloat()
                    val scale = maxOf(scaleX, scaleY)

                    val scaledW = bmp.width * scale
                    val scaledH = bmp.height * scale
                    val left = (canvasW - scaledW) / 2f
                    val top = (canvasH - scaledH) / 2f

                    val destRect = RectF(left, top, left + scaledW, top + scaledH)
                    canvas.drawBitmap(bmp, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                } else {
                    canvas.drawColor(Color.parseColor("#1a1a2e"))
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }
    }
}
