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
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val effectMode: String = config.effectMode
    private val raindrops = RaindropSimulation()
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
    private var uDropVelLoc = 0
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
        uniform vec4 uDropVel[8];
        uniform vec2 uResolution;

        // Teardrop SDF in velocity-aligned local space
        // Returns signed distance; negative = inside
        float sdTeardrop(vec2 p, vec2 velDir, float r, float deform) {
            if (deform < 0.01) return length(p) - r;

            // Rotate into velocity-aligned frame: +y=head, -y=tail
            float qx =  p.x * velDir.y - p.y * velDir.x;
            float qy =  p.x * velDir.x + p.y * velDir.y;

            // Stretch/squeeze
            qx *= 1.0 / (1.0 + deform * 0.12);
            qy *= 1.0 / (1.0 + deform * 0.55);

            // Shift toward tail
            qy -= deform * r * 0.12;

            float d = length(vec2(qx, qy));
            float angle = atan(qx, -qy + 0.001);
            float tip = 1.0 + deform * 0.45 * exp(-angle * angle * 4.0);
            return d - r * tip;
        }

        // 3×3 box blur for frosted-glass background
        vec4 foggedGlass(vec2 uv) {
            float rx = 0.007 / uAspectRatio;
            float ry = 0.007;
            vec4 s00 = texture2D(uTexture, clamp(uv + vec2(-rx, -ry), 0.0, 1.0));
            vec4 s01 = texture2D(uTexture, clamp(uv + vec2(0.0, -ry), 0.0, 1.0));
            vec4 s02 = texture2D(uTexture, clamp(uv + vec2( rx, -ry), 0.0, 1.0));
            vec4 s10 = texture2D(uTexture, clamp(uv + vec2(-rx, 0.0), 0.0, 1.0));
            vec4 s11 = texture2D(uTexture, uv);
            vec4 s12 = texture2D(uTexture, clamp(uv + vec2( rx, 0.0), 0.0, 1.0));
            vec4 s20 = texture2D(uTexture, clamp(uv + vec2(-rx,  ry), 0.0, 1.0));
            vec4 s21 = texture2D(uTexture, clamp(uv + vec2(0.0,  ry), 0.0, 1.0));
            vec4 s22 = texture2D(uTexture, clamp(uv + vec2( rx,  ry), 0.0, 1.0));

            vec4 col = s11 * 0.24 +
                       (s01 + s10 + s12 + s21) * 0.10 +
                       (s00 + s02 + s20 + s22) * 0.06;
            // Mist overlay: slight white hazing
            col.rgb = mix(col.rgb, vec3(0.88), 0.10);
            return col;
        }

        void main() {
            vec2 uv = vTexCoord;
            vec2 imageUV = uv * uCropScale + uCropOffset;
            vec4 fogColor = foggedGlass(imageUV);

            vec2 totalOffset = vec2(0.0);
            bool insideAny = false;

            for (int i = 0; i < 8; i++) {
                if (i >= uDropCount) break;
                vec2 dp = uDrops[i].xy;
                float r = uDrops[i].z;
                float deform = uDrops[i].w;
                if (r < 0.001) continue;

                vec2 delta = uv - dp;
                delta.x *= uAspectRatio;
                float sd = sdTeardrop(delta, uDropVel[i].xy, r, deform);
                if (sd >= 0.0) continue;

                insideAny = true;
                float nd = clamp(1.0 + sd / r, 0.0, 1.0);
                float lens = (1.0 - nd * nd) * 0.28;
                vec2 dir = length(delta) < 0.001 ? vec2(0.0) : normalize(delta);
                totalOffset += dir * lens * (r + deform * r * 0.15);
            }

            vec4 color;
            if (insideAny) {
                // Clear view through water drop with lens magnification
                vec2 clearUV = imageUV + totalOffset;
                clearUV = clamp(clearUV, 0.0, 1.0);
                color = texture2D(uTexture, clearUV);

                // Overlay highlights per droplet
                for (int i = 0; i < 8; i++) {
                    if (i >= uDropCount) break;
                    vec2 dp = uDrops[i].xy;
                    float r = uDrops[i].z;
                    float deform = uDrops[i].w;
                    if (r < 0.001) continue;

                    vec2 delta = uv - dp;
                    delta.x *= uAspectRatio;
                    float sd = sdTeardrop(delta, uDropVel[i].xy, r, deform);
                    if (sd >= 0.0) continue;

                    float nd = clamp(1.0 + sd / r, 0.0, 1.0);

                    // Specular highlight
                    vec2 specOff = vec2(-0.35 / uAspectRatio, -0.35) * r;
                    vec2 specDelta = delta - specOff;
                    float ssd = length(specDelta);
                    float spec = max(0.0, 1.0 - ssd / (r * 0.12));
                    color.rgb += vec3(spec * 0.55);

                    // Dark refractive rim
                    float rim = smoothstep(0.7, 1.0, nd);
                    color.rgb *= (1.0 - rim * 0.30);

                    // Surface-tension inner ring
                    float ring = smoothstep(0.05, 0.20, nd) * (1.0 - smoothstep(0.25, 0.40, nd));
                    color.rgb += ring * 0.06;
                }
            } else {
                // Frosted glass background (no drop at this pixel)
                color = fogColor;
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
            uDropVelLoc = GLES20.glGetUniformLocation(program, "uDropVel")

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
            val velData = FloatArray(8 * 4)
            if (isRain) {
                raindrops.init(dropRadiusUV)
                raindrops.fillDropArray(dropData)
            }

            var lastFrameNs = System.nanoTime()

            // ─── Render loop ────────────────────────────────────────────────────
            while (running) {
                if (pendingSurfaceChange) {
                    handleSurfaceChange()
                    pendingSurfaceChange = false
                }

                if (paused) {
                    Thread.sleep(50)
                    lastFrameNs = System.nanoTime()
                    continue
                }

                val frameStart = System.nanoTime()
                val dt = minOf((frameStart - lastFrameNs) / 1_000_000_000f, 0.05f)
                lastFrameNs = frameStart

                // Update raindrop physics from tilt sensor
                if (isRain) {
                    raindrops.update(dt, tiltAx * uvScale, tiltAy * uvScale)
                    raindrops.fillDropArray(dropData)
                    raindrops.fillVelArray(velData)
                }

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
                    for (i in 0 until 8) {
                        GLES20.glUniform4f(
                            uDropVelLoc + i,
                            velData[i * 4],
                            velData[i * 4 + 1],
                            velData[i * 4 + 2],
                            velData[i * 4 + 3]
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
        }
    }
}

// ─── Raindrop physics simulation ─────────────────────────────────────────────
class RaindropSimulation {
    companion object {
        private const val DEAD_ZONE = 0.3f
        private const val FRICTION_SMALL = 0.993f  // small drops: low resistance
        private const val FRICTION_LARGE = 0.987f  // large drops: high resistance
        private const val FRICTION_DEAD = 0.90f
        private const val BOUNCE_RETAIN = 0.3f
        private const val MAX_SPEED_UV = 1.2f
        private const val MAX_DEFORM_SPEED = 0.5f  // UV/s for full teardrop
    }

    private class Raindrop(
        var x: Float, var y: Float,
        var radius: Float,
        var vx: Float = 0f, var vy: Float = 0f,
        var friction: Float = 0f,
        var deformation: Float = 0f
    )

    private var drops: Array<Raindrop?> = arrayOfNulls(8)
    fun init(baseRadiusUV: Float) {
        // Create drops with random positions and radii
        drops = Array(8) {
            val variation = baseRadiusUV * (0.8f + Random.nextFloat() * 0.4f)
            val margin = variation * 1.1f
            Raindrop(
                x = margin + Random.nextFloat() * (1f - margin * 2f),
                y = margin + Random.nextFloat() * (1f - margin * 2f),
                radius = variation
            )
        }
        // Assign per-drop friction: larger radius → more resistance → lower friction factor
        val radii = drops.map { it!!.radius }
        val minR = radii.min()
        val maxR = radii.max()
        val range = (maxR - minR).let { if (it < 0.0001f) 1f else it }
        for (d in drops) {
            val t = (d!!.radius - minR) / range  // 0=smallest, 1=largest
            d.friction = FRICTION_LARGE + (FRICTION_SMALL - FRICTION_LARGE) * (1f - t)
        }
    }

    // ax, ay: tilt acceleration in UV/s² (pre-scaled from m/s²)
    fun update(dt: Float, ax: Float, ay: Float) {
        val mag = kotlin.math.sqrt(ax * ax + ay * ay)

        if (mag < DEAD_ZONE) {
            // Dead zone: friction only, bring drops to rest
            for (i in drops.indices) {
                val d = drops[i]!!
                d.vx *= FRICTION_DEAD
                d.vy *= FRICTION_DEAD
                val speed = kotlin.math.sqrt(d.vx * d.vx + d.vy * d.vy)
                d.deformation = minOf(speed / MAX_DEFORM_SPEED, 1f)
                if (kotlin.math.abs(d.vx) < 0.0005f && kotlin.math.abs(d.vy) < 0.0005f) continue
                d.x = (d.x + d.vx * dt).coerceIn(d.radius, 1f - d.radius)
                d.y = (d.y + d.vy * dt).coerceIn(d.radius, 1f - d.radius)
            }
            return
        }

        for (i in drops.indices) {
            val d = drops[i]!!

            // Gravitational acceleration: v += a·dt
            d.vx += ax * dt
            d.vy += ay * dt

            // Air/glass resistance (friction proportional to velocity)
            d.vx *= d.friction
            d.vy *= d.friction

            // Terminal velocity cap
            val speed = kotlin.math.sqrt(d.vx * d.vx + d.vy * d.vy)
            if (speed > MAX_SPEED_UV) {
                d.vx = d.vx / speed * MAX_SPEED_UV
                d.vy = d.vy / speed * MAX_SPEED_UV
            }
            d.deformation = minOf(speed / MAX_DEFORM_SPEED, 1f)

            // Position: s = v₀t + ½at²
            d.x += d.vx * dt + 0.5f * ax * dt * dt
            d.y += d.vy * dt + 0.5f * ay * dt * dt

            // Boundary bounce with energy loss
            if (d.x < d.radius) {
                d.x = d.radius
                d.vx = kotlin.math.abs(d.vx) * BOUNCE_RETAIN
            }
            if (d.x > 1f - d.radius) {
                d.x = 1f - d.radius
                d.vx = -kotlin.math.abs(d.vx) * BOUNCE_RETAIN
            }
            if (d.y < d.radius) {
                d.y = d.radius
                d.vy = kotlin.math.abs(d.vy) * BOUNCE_RETAIN
            }
            if (d.y > 1f - d.radius) {
                d.y = 1f - d.radius
                d.vy = -kotlin.math.abs(d.vy) * BOUNCE_RETAIN
            }
        }
    }

    fun fillDropArray(arr: FloatArray) {
        for (i in drops.indices) {
            val d = drops[i]!!
            arr[i * 4] = d.x
            arr[i * 4 + 1] = d.y
            arr[i * 4 + 2] = d.radius
            arr[i * 4 + 3] = d.deformation
        }
    }

    fun fillVelArray(arr: FloatArray) {
        for (i in drops.indices) {
            val d = drops[i]!!
            val speed = kotlin.math.sqrt(d.vx * d.vx + d.vy * d.vy)
            val invSpeed = if (speed > 0.0001f) 1f / speed else 0f
            arr[i * 4] = d.vx * invSpeed        // normalised vx
            arr[i * 4 + 1] = d.vy * invSpeed    // normalised vy
            arr[i * 4 + 2] = d.deformation
            arr[i * 4 + 3] = 0f
        }
    }
}
