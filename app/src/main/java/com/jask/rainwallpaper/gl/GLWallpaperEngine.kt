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
import kotlin.random.Random

class GLWallpaperEngine(
    private val holder: SurfaceHolder,
    private val bitmap: Bitmap?,
    config: Config
) {
    data class Config(
        val effectMode: String = "none",
        val dropRadiusUV: Float = 0.016f // ~0.5cm diameter on typical screen
    )

    private var renderThread: RenderThread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pendingSurfaceChange = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val effectMode: String = config.effectMode
    private val raindrops = RaindropSimulation()
    private val dropRadiusUV: Float = config.dropRadiusUV

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
    private var uDropCountLoc = 0
    private var uDropsLoc = 0
    private var uResolutionLoc = 0
    private var uAspectRatioLoc = 0
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

    private val FRAGMENT_SHADER_RAIN = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uCropOffset;
        uniform vec2 uCropScale;
        uniform float uAspectRatio;
        uniform int uDropCount;
        uniform vec4 uDrops[8];
        uniform vec2 uResolution;

        // Distance in aspect-corrected UV space (height-normalised)
        float dropDist(vec2 uv, vec2 center) {
            vec2 d = uv - center;
            d.x *= uAspectRatio;
            return length(d);
        }

        void main() {
            vec2 uv = vTexCoord;
            vec2 imageUV = uv * uCropScale + uCropOffset;

            // Accumulate lens displacement from all raindrops
            vec2 totalOffset = vec2(0.0);

            for (int i = 0; i < 8; i++) {
                if (i >= uDropCount) break;
                vec2 dp = uDrops[i].xy;
                float r = uDrops[i].z;
                if (r < 0.001) continue;

                float dist = dropDist(uv, dp);
                if (dist >= r) continue;

                // Lens magnification: sample outward from centre
                float nd = dist / r;
                float lens = (1.0 - nd * nd) * 0.28;
                vec2 dir = dist < 0.001 ? vec2(0.0) : normalize(uv - dp);
                totalOffset += dir * lens * r;
            }

            vec2 finalUV = imageUV + totalOffset;
            finalUV = clamp(finalUV, 0.0, 1.0);
            vec4 color = texture2D(uTexture, finalUV);

            // Overlay highlights per droplet
            for (int i = 0; i < 8; i++) {
                if (i >= uDropCount) break;
                vec2 dp = uDrops[i].xy;
                float r = uDrops[i].z;
                if (r < 0.001) continue;

                float dist = dropDist(uv, dp);
                if (dist >= r) continue;

                float nd = dist / r;

                // Specular highlight (top-left offset in aspect-corrected space)
                vec2 specOff = vec2(-0.35 / uAspectRatio, -0.35) * r;
                vec2 specDelta = uv - (dp + specOff);
                specDelta.x *= uAspectRatio;
                float sd = length(specDelta);
                float spec = max(0.0, 1.0 - sd / (r * 0.12));
                color.rgb += vec3(spec * 0.55);

                // Dark refractive rim at droplet edge
                float rim = smoothstep(0.7, 1.0, nd);
                color.rgb *= (1.0 - rim * 0.30);

                // Surface-tension inner ring (subtle bright band)
                float ring = smoothstep(0.05, 0.20, nd) * (1.0 - smoothstep(0.25, 0.40, nd));
                color.rgb += ring * 0.06;
            }

            gl_FragColor = color;
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
            if (!initEGL()) return

            textureId = loadTexture(bitmap)
            val fragShader = if (effectMode == "rain") FRAGMENT_SHADER_RAIN else FRAGMENT_SHADER_NO_EFFECT
            program = createProgram(VERTEX_SHADER, fragShader)
            if (program == 0) {
                releaseEGL()
                return
            }

            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uCropOffsetLoc = GLES20.glGetUniformLocation(program, "uCropOffset")
            uCropScaleLoc = GLES20.glGetUniformLocation(program, "uCropScale")
            uDropCountLoc = GLES20.glGetUniformLocation(program, "uDropCount")
            uDropsLoc = GLES20.glGetUniformLocation(program, "uDrops")
            uResolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")
            uAspectRatioLoc = GLES20.glGetUniformLocation(program, "uAspectRatio")

            // Full-screen quad: two triangles (strip = 4 verts)
            // x, y, u, v
            val verts = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
            val idx = byteArrayOf(0, 1, 2, 1, 3, 2)

            GLES20.glGenBuffers(2, vboIds, 0)

            // VBO for vertices
            val vb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            vb.order(java.nio.ByteOrder.nativeOrder())
            val fvb = vb.asFloatBuffer()
            fvb.put(verts)
            fvb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, fvb, GLES20.GL_STATIC_DRAW)

            // VBO for indices
            val ib = java.nio.ByteBuffer.allocateDirect(idx.size)
            ib.put(idx)
            ib.position(0)
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
            val (cropOff, cropScale) = computeCropParams(imgW, imgH, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            GLES20.glUniform2f(uCropOffsetLoc, cropOff[0], cropOff[1])
            GLES20.glUniform2f(uCropScaleLoc, cropScale[0], cropScale[1])
            val aspectRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
            GLES20.glUniform1f(uAspectRatioLoc, aspectRatio)

            val isRain = effectMode == "rain"
            val dropData = FloatArray(8 * 4)
            if (isRain) {
                raindrops.init(dropRadiusUV)
                raindrops.fillDropArray(dropData)
            }

            // ─── Render loop ────────────────────────────────────────────────────
            while (running) {
                if (pendingSurfaceChange) {
                    handleSurfaceChange()
                    pendingSurfaceChange = false
                }

                if (paused) {
                    Thread.sleep(50)
                    continue
                }

                val frameStart = System.nanoTime()

                // Draw
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)

                if (isRain) {
                    GLES20.glUniform1i(uDropCountLoc, 8)
                    for (i in 0 until 8) {
                        GLES20.glUniform4f(
                            uDropsLoc + i,
                            dropData[i * 4],
                            dropData[i * 4 + 1],
                            dropData[i * 4 + 2],
                            dropData[i * 4 + 3]
                        )
                    }
                } else {
                    GLES20.glUniform1i(uDropCountLoc, 0)
                }

                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_BYTE, 0)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                // Throttle ~60 fps
                val elapsed = (System.nanoTime() - frameStart) / 1_000_000L
                if (elapsed < 16) {
                    Thread.sleep(16 - elapsed)
                }
            }

            // Cleanup
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteBuffers(2, vboIds, 0)
            releaseEGL()
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
            GLES20.glUniform1f(uAspectRatioLoc, surfaceWidth.toFloat() / surfaceHeight.toFloat())
        }
    }
}

// ─── Raindrop physics simulation ─────────────────────────────────────────────

class RaindropSimulation {
    private class Raindrop(val x: Float, val y: Float, var radius: Float)

    private var drops: Array<Raindrop?> = arrayOfNulls(8)

    // Called once before render loop — creates 8 static drops
    fun init(baseRadiusUV: Float) {
        // ±0.1cm diameter variation → ±20% radius
        drops = Array(8) {
            val variation = baseRadiusUV * (0.8f + Random.nextFloat() * 0.4f)
            // Position within visible area with margin
            val margin = variation * 1.1f
            Raindrop(
                x = margin + Random.nextFloat() * (1f - margin * 2f),
                y = margin + Random.nextFloat() * (1f - margin * 2f),
                radius = variation
            )
        }
    }

    fun fillDropArray(arr: FloatArray) {
        for (i in drops.indices) {
            val d = drops[i]!!
            arr[i * 4] = d.x
            arr[i * 4 + 1] = d.y
            arr[i * 4 + 2] = d.radius
            arr[i * 4 + 3] = 0f
        }
    }
}
