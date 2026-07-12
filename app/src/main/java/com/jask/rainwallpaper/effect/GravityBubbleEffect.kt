package com.jask.rainwallpaper.effect

import kotlin.random.Random

class GravityBubbleEffect : GLEffect {
    override val key = "gravity_bubble"
    override val label = "Gravity Bubbles"

    override val fragmentShader = """
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

    override fun createSimulation(): Simulation = BubbleSimulation()
}

// ─── Gravity bubble physics simulation ───────────────────────────────────────

class BubbleSimulation : Simulation {
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

    override fun init(baseRadiusUV: Float) {
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
    override fun update(dt: Float, ax: Float, ay: Float): Boolean {
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

    override fun fillDropArray(arr: FloatArray) {
        for (i in bubbles.indices) {
            val b = bubbles[i]!!
            arr[i * 4] = b.x
            arr[i * 4 + 1] = b.y
            arr[i * 4 + 2] = b.radius
            arr[i * 4 + 3] = b.deformation
        }
    }

    override fun fillVelArray(arr: FloatArray) {
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
