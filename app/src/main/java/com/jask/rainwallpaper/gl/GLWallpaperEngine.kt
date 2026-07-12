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
    private val effectMode: String = config.effectMode
    private val bubbles = BubbleSimulation()
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

    private val FRAGMENT_SHADER_BUBBLE = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uCropOffset;
        uniform vec2 uCropScale;
        uniform float uAspectRatio;
        uniform int uBubbleCount;
        uniform vec4 uBubbles[8];
        uniform vec4 uBubbleVel[8];
        uniform vec2 uResolution;

        float sdBubble(vec2 p, vec2 velDir, float r, float deform) {
            if (deform < 0.01) return length(p) - r;
            float qx =  p.x * velDir.y - p.y * velDir.x;
            float qy =  p.x * velDir.x + p.y * velDir.y;
            qx *= 1.0 / (1.0 + deform * 0.12);
            qy *= 1.0 / (1.0 + deform * 0.55);
            qy -= deform * r * 0.12;
            float d = length(vec2(qx, qy));
            float angle = atan(qx, -qy + 0.001);
            float tip = 1.0 + deform * 0.45 * exp(-angle * angle * 4.0);
            return d - r * tip;
        }

        // Dome surface normal for a bubble (LiquidGlass-style)
        vec3 bubbleNormal(vec2 delta, float r, float sd) {
            float nd = clamp(-sd / r, 0.0, 1.0);  // 0 at edge, 1 at centre
            vec2 radial = length(delta) < 0.0001 ? vec2(0.0) : normalize(delta);
            // Steep wall at edge (nd=0), flat at centre (nd=1)
            return normalize(vec3(
                radial * (1.0 - nd) * 0.7,
                0.3 + nd * 0.7
            ));
        }

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        void main() {
            vec2 uv = vTexCoord;
            vec2 imageUV = uv * uCropScale + uCropOffset;

            vec2 totalOffset = vec2(0.0);
            vec3 specAccum = vec3(0.0);
            float rimAccum = 0.0;
            float bestND = -1.0;
            float bestR = 0.0;

            for (int i = 0; i < 8; i++) {
                if (i >= uBubbleCount) break;
                vec2 dp = uBubbles[i].xy;
                float r = uBubbles[i].z;
                float deform = uBubbles[i].w;
                if (r < 0.001) continue;

                vec2 delta = uv - dp;
                delta.x *= uAspectRatio;
                float sd = sdBubble(delta, uBubbleVel[i].xy, r, deform);
                if (sd >= 0.0) continue;

                float nd = clamp(-sd / r, 0.0, 1.0);
                if (nd > bestND) { bestND = nd; bestR = r; }

                // Convex lens: strongest at centre, zero at edge
                float lens = nd * nd * 0.35;
                vec2 dir = length(delta) < 0.001 ? vec2(0.0) : normalize(delta);
                totalOffset += dir * lens * (r + deform * r * 0.15);

                // Dome surface normal for specular
                vec3 normal = bubbleNormal(delta, r, sd);
                vec3 lightDir = normalize(vec3(-0.5 / uAspectRatio, -0.5, 1.0));
                vec3 halfVec = normalize(lightDir + vec3(0.0, 0.0, 1.0));
                float spec = pow(max(dot(normal, halfVec), 0.0), 48.0);
                specAccum += spec * 0.4 * vec3(1.0);

                // Sharp dark outline right at the edge
                float edge = smoothstep(0.0, 0.03, nd) * (1.0 - smoothstep(0.03, 0.10, nd));
                rimAccum += edge * 0.55;

                // Bright specular ring just inside the edge
                float ring = smoothstep(0.08, 0.14, nd) * (1.0 - smoothstep(0.14, 0.22, nd));
                specAccum += ring * 0.20 * vec3(1.0);
            }

            vec2 finalUV = imageUV + totalOffset;
            finalUV = clamp(finalUV, 0.0, 1.0);

            vec4 color;
            if (bestND >= 0.0) {
                // Inside at least one bubble: apply radial blur gradient
                float blurFactor = (1.0 - bestND);  // 0=centre, 1=edge
                blurFactor = blurFactor * blurFactor; // quadratic falloff
                float blurRadius = blurFactor * bestR * 0.5;
                float rx = blurRadius / uAspectRatio;
                float ry = blurRadius;

                vec4 c0 = texture2D(uTexture, finalUV);
                vec4 c1 = texture2D(uTexture, clamp(finalUV + vec2(rx, 0.0), 0.0, 1.0));
                vec4 c2 = texture2D(uTexture, clamp(finalUV - vec2(rx, 0.0), 0.0, 1.0));
                vec4 c3 = texture2D(uTexture, clamp(finalUV + vec2(0.0, ry), 0.0, 1.0));
                vec4 c4 = texture2D(uTexture, clamp(finalUV - vec2(0.0, ry), 0.0, 1.0));

                float w = blurFactor * 0.35;
                float wc = 1.0 - w * 4.0;
                color = c0 * wc + (c1 + c2 + c3 + c4) * w;

                // Apply specular and rim
                color.rgb += specAccum;
                color.rgb *= (1.0 - rimAccum);
            } else {
                // Background: clear image with subtle glass tint
                color = texture2D(uTexture, finalUV);
                float y = luma(color.rgb);
                color.rgb = mix(color.rgb, y * vec3(0.92, 0.96, 1.0), 0.06);
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
            var isBubble = false
            var dropData = FloatArray(8)
            var velData = FloatArray(8)
            var lastFrameNs = 0L
            var idleFrames = 0

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
                    continue
                }

                if (!eglActive) {
                    if (!initEGL()) { running = false; break }
                    textureId = loadTexture(bitmap)
                    val fragSrc = if (effectMode == "gravity_bubble") FRAGMENT_SHADER_BUBBLE else FRAGMENT_SHADER_NO_EFFECT
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

                    isBubble = effectMode == "gravity_bubble"
                    dropData = FloatArray(8 * 4)
                    velData = FloatArray(8 * 4)
                    if (isBubble) {
                        bubbles.init(dropRadiusUV)
                        bubbles.fillDropArray(dropData)
                    }
                    lastFrameNs = System.nanoTime()
                    idleFrames = 0
                    eglActive = true
                }

                val frameStart = System.nanoTime()
                val dt = minOf((frameStart - lastFrameNs) / 1_000_000_000f, 0.05f)
                lastFrameNs = frameStart

                val anyMoving = if (isBubble) {
                    val m = bubbles.update(dt, tiltAx * uvScale, tiltAy * uvScale)
                    bubbles.fillDropArray(dropData)
                    bubbles.fillVelArray(velData)
                    m
                } else false

                idleFrames = if (anyMoving) 0 else (idleFrames + 1)
                if (idleFrames > 30) {
                    Thread.sleep(200)
                    continue
                }

                // Draw
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                if (isBubble) {
                    GLES20.glUniform1i(uBubbleCountLoc, 8)
                    for (i in 0 until 8) {
                        GLES20.glUniform4f(uBubblesLoc + i, dropData[i*4], dropData[i*4+1], dropData[i*4+2], dropData[i*4+3])
                        GLES20.glUniform4f(uBubbleVelLoc + i, velData[i*4], velData[i*4+1], velData[i*4+2], velData[i*4+3])
                    }
                } else {
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
        }
    }
}

// ─── Gravity bubble physics simulation ───────────────────────────────────────
class BubbleSimulation {
    companion object {
        private const val DEAD_ZONE = 0.3f
        private const val FRICTION_SMALL = 0.993f  // small bubbles: low resistance
        private const val FRICTION_LARGE = 0.987f  // large bubbles: high resistance
        private const val FRICTION_DEAD = 0.90f
        private const val BOUNCE_RETAIN = 0.3f
        private const val MAX_SPEED_UV = 1.2f
        private const val MAX_DEFORM_SPEED = 0.5f  // UV/s for full bubble deformation
    }

    private class Bubble(
        var x: Float, var y: Float,
        var radius: Float,
        var vx: Float = 0f, var vy: Float = 0f,
        var friction: Float = 0f,
        var deformation: Float = 0f
    )

    private var bubbles: Array<Bubble?> = arrayOfNulls(8)
    fun init(baseRadiusUV: Float) {
        // Create bubbles with random positions and radii
        bubbles = Array(8) {
            val variation = baseRadiusUV * (0.8f + Random.nextFloat() * 0.4f)
            val margin = variation * 1.1f
            Bubble(
                x = margin + Random.nextFloat() * (1f - margin * 2f),
                y = margin + Random.nextFloat() * (1f - margin * 2f),
                radius = variation
            )
        }
        // Assign per-bubble friction: larger radius → more resistance → lower friction factor
        val radii = bubbles.map { it!!.radius }
        val minR = radii.min()
        val maxR = radii.max()
        val range = (maxR - minR).let { if (it < 0.0001f) 1f else it }
        for (b in bubbles) {
            val t = (b!!.radius - minR) / range  // 0=smallest, 1=largest
            b.friction = FRICTION_LARGE + (FRICTION_SMALL - FRICTION_LARGE) * (1f - t)
        }
    }

    // ax, ay: tilt acceleration in UV/s² (pre-scaled from m/s²)
    fun update(dt: Float, ax: Float, ay: Float): Boolean {
        val mag = kotlin.math.sqrt(ax * ax + ay * ay)

        if (mag < DEAD_ZONE) {
            var anyMoving = false
            for (i in bubbles.indices) {
                val b = bubbles[i]!!
                b.vx *= FRICTION_DEAD
                b.vy *= FRICTION_DEAD
                val speed = kotlin.math.sqrt(b.vx * b.vx + b.vy * b.vy)
                b.deformation = minOf(speed / MAX_DEFORM_SPEED, 1f)
                if (kotlin.math.abs(b.vx) < 0.0002f && kotlin.math.abs(b.vy) < 0.0002f) continue
                anyMoving = true
                b.x = (b.x + b.vx * dt).coerceIn(b.radius, 1f - b.radius)
                b.y = (b.y + b.vy * dt).coerceIn(b.radius, 1f - b.radius)
            }
            return anyMoving
        }

        for (i in bubbles.indices) {
            val b = bubbles[i]!!
            b.vx += ax * dt
            b.vy += ay * dt
            b.vx *= b.friction
            b.vy *= b.friction
            val speed = kotlin.math.sqrt(b.vx * b.vx + b.vy * b.vy)
            if (speed > MAX_SPEED_UV) {
                b.vx = b.vx / speed * MAX_SPEED_UV
                b.vy = b.vy / speed * MAX_SPEED_UV
            }
            b.deformation = minOf(speed / MAX_DEFORM_SPEED, 1f)
            b.x += b.vx * dt + 0.5f * ax * dt * dt
            b.y += b.vy * dt + 0.5f * ay * dt * dt

            if (b.x < b.radius) { b.x = b.radius; b.vx = kotlin.math.abs(b.vx) * BOUNCE_RETAIN }
            if (b.x > 1f - b.radius) { b.x = 1f - b.radius; b.vx = -kotlin.math.abs(b.vx) * BOUNCE_RETAIN }
            if (b.y < b.radius) { b.y = b.radius; b.vy = kotlin.math.abs(b.vy) * BOUNCE_RETAIN }
            if (b.y > 1f - b.radius) { b.y = 1f - b.radius; b.vy = -kotlin.math.abs(b.vy) * BOUNCE_RETAIN }
        }
        return true
    }

    fun fillDropArray(arr: FloatArray) {
        for (i in bubbles.indices) {
            val b = bubbles[i]!!
            arr[i * 4] = b.x
            arr[i * 4 + 1] = b.y
            arr[i * 4 + 2] = b.radius
            arr[i * 4 + 3] = b.deformation
        }
    }

    fun fillVelArray(arr: FloatArray) {
        for (i in bubbles.indices) {
            val b = bubbles[i]!!
            val speed = kotlin.math.sqrt(b.vx * b.vx + b.vy * b.vy)
            val invSpeed = if (speed > 0.0001f) 1f / speed else 0f
            arr[i * 4] = b.vx * invSpeed        // normalised vx
            arr[i * 4 + 1] = b.vy * invSpeed    // normalised vy
            arr[i * 4 + 2] = b.deformation
            arr[i * 4 + 3] = 0f
        }
    }
}
