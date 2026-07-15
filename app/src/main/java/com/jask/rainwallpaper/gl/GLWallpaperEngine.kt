@file:Suppress("unused")

package com.jask.rainwallpaper.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.SurfaceHolder
import com.jask.rainwallpaper.effect.GLEffect
import com.jask.rainwallpaper.effect.Simulation

class GLWallpaperEngine(
    private val holder: SurfaceHolder,
    private val bitmap: Bitmap?,
    config: Config
) {
    data class Config(
        val effect: GLEffect? = null,
        val dropRadiusUV: Float = 0.016f,
        val screenHeightCm: Float = 15f
    )

    private var renderThread: RenderThread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pendingSurfaceChange = false
    @Volatile private var eglActive = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val effect: GLEffect? = config.effect
    private val dropRadiusUV: Float = config.dropRadiusUV
    // Tilt acceleration in UV/s², written by sensor listener, read by render thread
    @Volatile var tiltAx = 0f
    @Volatile var tiltAy = 0f
    private val uvScale: Float = 2.0f / config.screenHeightCm // m/s² → UV/s²
    fun start(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        running = true
        renderThread = RenderThread().apply { start() }
    }

    fun stop() {
        running = false
        renderThread?.join(2000)
        renderThread = null
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun surfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        pendingSurfaceChange = true
    }

    // ─── EGL ────────────────────────────────────────────────────────────────────

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private fun initEGL(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            return false
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        return createEGLSurface()
    }

    private fun createEGLSurface(): Boolean {
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, holder.surface, null, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        EGL14.eglSwapInterval(eglDisplay, 1) // VSync-driven rendering
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    // ─── Shaders (GLES 2.0) ─────────────────────────────────────────────────────

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uCropOffsetLoc = 0
    private var uCropScaleLoc = 0
    private var uBubbleCountLoc = 0
    private var uBubblesLoc = 0
    private var uResolutionLoc = 0
    private var uAspectRatioLoc = 0
    private var uBubbleVelLoc = 0
    private var uTimeLoc = 0
    private var vboIds = IntArray(2)

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aTexCoord;
            gl_Position = aPosition;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER_NO_EFFECT = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uCropOffset;
        uniform vec2 uCropScale;
        void main() {
            vec2 uv = vTexCoord * uCropScale + uCropOffset;
            gl_FragColor = texture2D(uTexture, uv);
        }
    """.trimIndent()


    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    // ─── GL init ────────────────────────────────────────────────────────────────

    private var textureId = 0

    private fun loadTexture(bmp: Bitmap?): Int {
        if (bmp == null || bmp.isRecycled) return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    private fun computeCropParams(
        imgW: Float, imgH: Float,
        screenW: Float, screenH: Float
    ): Pair<FloatArray, FloatArray> {
        val imgAspect = imgW / imgH
        val screenAspect = screenW / screenH
        var scaleX = 1f
        var scaleY = 1f
        var offX = 0f
        var offY = 0f
        if (imgAspect > screenAspect) {
            // Image wider → crop horizontally
            val crop = (1f - screenAspect / imgAspect) / 2f
            scaleX = 1f - 2f * crop
            offX = crop
        } else {
            val crop = (1f - imgAspect / screenAspect) / 2f
            scaleY = 1f - 2f * crop
            offY = crop
        }
        return Pair(floatArrayOf(offX, offY), floatArrayOf(scaleX, scaleY))
    }

    // ─── Render thread ──────────────────────────────────────────────────────────

    private inner class RenderThread : Thread("GL-Render") {
        override fun run() {
            var simulation: Simulation? = null
            var dropData = FloatArray(10)
            var velData = FloatArray(10)
            var lastFrameNs = 0L
            var nextFrameNs = 0L
            var idleFrames = 0
            var timeSeconds = 0f
            val targetFps = (effect?.targetFps ?: 60).coerceIn(1, 120)
            val targetFrameNs = 1_000_000_000L / targetFps

            // ─── Render loop ────────────────────────────────────────────────────
            while (running) {
                if (pendingSurfaceChange) {
                    handleSurfaceChange()
                    pendingSurfaceChange = false
                }

                if (paused) {
                    if (eglActive) {
                        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                        GLES20.glDeleteProgram(program)
                        GLES20.glDeleteBuffers(2, vboIds, 0)
                        releaseEGL()
                        eglActive = false
                    }
                    Thread.sleep(500)
                    lastFrameNs = System.nanoTime()
                    nextFrameNs = lastFrameNs
                    continue
                }

                if (!eglActive) {
                    if (!initEGL()) { running = false; break }
                    textureId = loadTexture(bitmap)
                    val fragSrc = effect?.fragmentShader ?: FRAGMENT_SHADER_NO_EFFECT
                    program = createProgram(VERTEX_SHADER, fragSrc)
                    if (program == 0) { releaseEGL(); running = false; break }

                    aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
                    aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
                    uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
                    uCropOffsetLoc = GLES20.glGetUniformLocation(program, "uCropOffset")
                    uCropScaleLoc = GLES20.glGetUniformLocation(program, "uCropScale")
                    uBubbleCountLoc = GLES20.glGetUniformLocation(program, "uBubbleCount")
                    uBubblesLoc = GLES20.glGetUniformLocation(program, "uBubbles")
                    uResolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")
                    uAspectRatioLoc = GLES20.glGetUniformLocation(program, "uAspectRatio")
                    uBubbleVelLoc = GLES20.glGetUniformLocation(program, "uBubbleVel")
                    uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")

                    val verts = floatArrayOf(
                        -1f, -1f, 0f, 1f,  1f, -1f, 1f, 1f,
                        -1f, 1f, 0f, 0f,  1f, 1f, 1f, 0f
                    )
                    val idx = byteArrayOf(0, 1, 2, 1, 3, 2)
                    GLES20.glGenBuffers(2, vboIds, 0)

                    val vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
                    vb.order(java.nio.ByteOrder.nativeOrder())
                    vb.asFloatBuffer().apply { put(verts); position(0) }
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
                    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vb.asFloatBuffer(), GLES20.GL_STATIC_DRAW)

                    val ib = java.nio.ByteBuffer.allocateDirect(idx.size).apply { put(idx); position(0) }
                    GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
                    GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size, ib, GLES20.GL_STATIC_DRAW)

                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
                    GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, 0)
                    GLES20.glEnableVertexAttribArray(aPositionLoc)
                    GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, 8)
                    GLES20.glEnableVertexAttribArray(aTexCoordLoc)

                    GLES20.glUseProgram(program)
                    GLES20.glUniform1i(uTextureLoc, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

                    val imgW = (bitmap?.width ?: 1).toFloat()
                    val imgH = (bitmap?.height ?: 1).toFloat()
                    val (co, cs) = computeCropParams(imgW, imgH, surfaceWidth.toFloat(), surfaceHeight.toFloat())
                    GLES20.glUniform2f(uCropOffsetLoc, co[0], co[1])
                    GLES20.glUniform2f(uCropScaleLoc, cs[0], cs[1])
                    GLES20.glUniform1f(uAspectRatioLoc, surfaceWidth.toFloat() / surfaceHeight.toFloat())
                    GLES20.glUniform2f(uResolutionLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())

                    simulation = effect?.createSimulation()
                    if (effect?.usesSimulationUniforms == true) {
                        dropData = FloatArray(10 * 4)
                        velData = FloatArray(10 * 4)
                    }
                    simulation?.let {
                        it.init(dropRadiusUV)
                        if (effect?.usesSimulationUniforms == true) {
                            it.fillDropArray(dropData)
                            it.fillVelArray(velData)
                        }
                    }
                    lastFrameNs = System.nanoTime()
                    nextFrameNs = lastFrameNs
                    idleFrames = 0
                    eglActive = true
                }

                var frameStart = System.nanoTime()
                val remainingNs = nextFrameNs - frameStart
                if (remainingNs > 0L) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remainingNs)
                    frameStart = System.nanoTime()
                }
                nextFrameNs = maxOf(nextFrameNs + targetFrameNs, frameStart)

                val dt = minOf((frameStart - lastFrameNs) / 1_000_000_000f, 0.05f)
                lastFrameNs = frameStart
                timeSeconds += dt

                val simulationMoving = simulation?.let {
                    val moving = it.update(dt, tiltAx * uvScale, tiltAy * uvScale)
                    if (effect?.usesSimulationUniforms == true) {
                        it.fillDropArray(dropData)
                        it.fillVelArray(velData)
                    }
                    moving
                } ?: false
                val anyMoving = simulationMoving || effect?.isTimeDriven == true

                idleFrames = if (anyMoving) 0 else (idleFrames + 1)
                if (idleFrames > 30) {
                    Thread.sleep(200)
                    continue
                }

                // Draw
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                if (uTimeLoc >= 0) {
                    GLES20.glUniform1f(uTimeLoc, timeSeconds)
                }
                if (simulation != null && effect?.usesSimulationUniforms == true) {
                    if (uBubbleCountLoc >= 0) {
                        GLES20.glUniform1i(uBubbleCountLoc, 10)
                    }
                    for (i in 0 until 10) {
                        if (uBubblesLoc >= 0) {
                            GLES20.glUniform4f(uBubblesLoc + i, dropData[i*4], dropData[i*4+1], dropData[i*4+2], dropData[i*4+3])
                        }
                        if (uBubbleVelLoc >= 0) {
                            GLES20.glUniform4f(uBubbleVelLoc + i, velData[i*4], velData[i*4+1], velData[i*4+2], velData[i*4+3])
                        }
                    }
                } else if (uBubbleCountLoc >= 0) {
                    GLES20.glUniform1i(uBubbleCountLoc, 0)
                }
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_BYTE, 0)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }

            // Final cleanup
            if (eglActive) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteBuffers(2, vboIds, 0)
                releaseEGL()
            }
        }

        private fun handleSurfaceChange() {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
            createEGLSurface()
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)

            // Recompute crop
            val imgW = (bitmap?.width ?: 1).toFloat()
            val imgH = (bitmap?.height ?: 1).toFloat()
            val (cropOff, cropScale) = computeCropParams(imgW, imgH, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            GLES20.glUseProgram(program)
            GLES20.glUniform2f(uCropOffsetLoc, cropOff[0], cropOff[1])
            GLES20.glUniform2f(uCropScaleLoc, cropScale[0], cropScale[1])
            GLES20.glUniform2f(uResolutionLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        }
    }
}
