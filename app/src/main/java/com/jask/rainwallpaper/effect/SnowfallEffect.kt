package com.jask.rainwallpaper.effect

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class SnowfallEffect : GLEffect {
    override val key = "snowfall"
    override val label = "Snowfall"

    override val fragmentShader = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uCropOffset;
        uniform vec2 uCropScale;
        uniform float uAspectRatio;
        uniform int uBubbleCount;
        uniform vec4 uBubbles[10];
        uniform vec4 uBubbleVel[10];
        uniform vec2 uResolution;

        // ─── Per-flake hash (replaces reference's iChannel0 texture lookup) ──────
        float hash12(vec2 p) {
            float h = dot(p, vec2(127.1, 311.7));
            return fract(sin(h) * 43758.5453);
        }

        // ─── Flake type A: stellar dendrite (long thin arms, no rings) ───────────
        float flakeStellar(vec2 p, float sz, float f) {
            float r = length(p) / sz;
            if (r > 1.08) return 0.0;
            float angle = atan(p.y, p.x) + hash12(vec2(f)) * 6.2831;
            float numPeds = 6.0;
            float pedVal   = sin(angle * numPeds) * 0.5 + 0.5;
            float arms     = pow(pedVal, 6.0);
            arms *= smoothstep(0.95, 0.15, r);
            float centre = exp(-r * r * 200.0) * 0.5;
            float shape  = max(arms, centre);
            shape *= 1.0 - smoothstep(0.90, 1.02, r);
            return clamp(shape, 0.0, 1.0);
        }

        // ─── Flake type B: hexagonal plate (flat hexagon, internal divisions) ─────
        float flakePlate(vec2 p, float sz, float f) {
            float r = length(p) / sz;
            float angle = atan(p.y, p.x) + hash12(vec2(f)) * 6.2831;
            float sector  = 1.047197551;
            float a       = mod(angle + sector, 2.0 * sector) - sector;
            float hexR    = 0.92 / cos(a);
            float hexEdge = 1.0 - smoothstep(0.85, 0.92, r / hexR);
            float innerLine = 1.0 - smoothstep(0.38, 0.42, abs(r - 0.40));
            innerLine *= smoothstep(0.9, 0.2, abs(a) / sector);
            float innerLine2 = 1.0 - smoothstep(0.68, 0.72, abs(r - 0.70));
            innerLine2 *= smoothstep(0.9, 0.2, abs(a) / sector);
            float shape = max(hexEdge, max(innerLine * 0.5, innerLine2 * 0.4));
            return clamp(shape, 0.0, 1.0);
        }

        // ─── Flake type C: classic dendrite (arms + rings, closest to reference) ──
        float flakeDendrite(vec2 p, float sz, float f) {
            float r = length(p) / sz;
            if (r > 1.08) return 0.0;
            float angle    = atan(p.y, p.x) + hash12(vec2(f)) * 6.2831;
            float numPeds  = floor(mix(5.0, 9.0, hash12(vec2(f + 0.1))));
            float numRings = 2.0 + hash12(vec2(f + 0.3)) * 3.0;
            float sizeVar  = 0.80 + hash12(vec2(f + 0.7)) * 0.20;
            float armPow   = 3.5  + hash12(vec2(f + 1.1)) * 1.5;
            float pedVal   = sin(angle * numPeds) * 0.5 + 0.5;
            float arms     = pow(pedVal, armPow);
            arms *= smoothstep(sizeVar, 0.25, r);
            float pedVal2  = (hash12(vec2(f + 1.7)) < 0.5) ? (1.0 - pedVal) : pedVal;
            float warpDist = mix(r * 0.8, r, pedVal2);
            float ringVal  = sin(warpDist / (sizeVar * 0.8) * 6.2831 * numRings - 1.5708) * 0.5 + 0.5;
            float rings    = pow(ringVal, 2.0);
            rings *= smoothstep(sizeVar * 0.8, 0.15, r) * 0.60;
            float shape = max(arms, rings);
            shape *= 1.0 - smoothstep(sizeVar * 0.72, sizeVar * 0.98, r);
            return clamp(shape, 0.0, 1.0);
        }

        // ─── Flake type D: sector plate (broad filled triangular sectors) ─────────
        float flakeSector(vec2 p, float sz, float f) {
            float r = length(p) / sz;
            if (r > 1.08) return 0.0;
            float angle   = atan(p.y, p.x) + hash12(vec2(f)) * 6.2831;
            float numPeds = floor(mix(5.0, 7.0, hash12(vec2(f + 0.2))));
            float pedVal  = sin(angle * numPeds) * 0.5 + 0.5;
            float sector  = pow(pedVal, 1.5);
            sector *= 1.0 - smoothstep(0.70, 0.95, r);
            float centre  = exp(-r * r * 30.0) * 0.4;
            float edge    = (1.0 - smoothstep(0.75, 0.85, r)) * 0.3;
            float shape   = max(sector, max(centre, edge));
            shape *= 1.0 - smoothstep(0.80, 0.95, r);
            return clamp(shape, 0.0, 1.0);
        }

        // ─── Dispatch: 4 visually distinct flake types ────────────────────────────
        float flakeShape(vec2 p, float sz, float f) {
            float type = hash12(vec2(f, 0.37));
            if (type < 0.25)      return flakeStellar(p, sz, f);
            else if (type < 0.50) return flakePlate(p, sz, f);
            else if (type < 0.75) return flakeDendrite(p, sz, f);
            else                  return flakeSector(p, sz, f);
        }

        void main() {
            vec2 uv = vTexCoord;
            vec2 imageUV = uv * uCropScale + uCropOffset;
            vec4 color = texture2D(uTexture, imageUV);

            float accum = 0.0;
            vec3  flakeColor = vec3(0.96, 0.97, 1.0); // bright cool-white

            for (int i = 0; i < 10; i++) {
                if (i >= uBubbleCount) break;
                vec2 fp = uBubbles[i].xy;
                float sz = uBubbles[i].z;
                float ph = uBubbles[i].w;
                if (sz < 0.0005) continue;

                vec2 delta = uv - fp;
                delta.x *= uAspectRatio;

                float shape = flakeShape(delta, sz, ph);
                accum = max(accum, shape);

                // Outer glow halo
                float d = length(delta);
                float glow = exp(-d * d * 55.0 / (sz * sz)) * 0.18;
                accum = max(accum, glow);
            }

            color.rgb = mix(color.rgb, flakeColor, clamp(accum, 0.0, 1.0) * 0.90);
            gl_FragColor = color;
        }
    """.trimIndent()

    override fun createSimulation(): Simulation = SnowflakeSimulation()
}

// ─── Snowflake physics simulation ────────────────────────────────────────────

class SnowflakeSimulation : Simulation {
    companion object {
        /** Physical fall speed in cm/s — constant across screen sizes. */
        private const val SPEED_CM_S = 3.2f

        /** The radius (cm) used by the engine to compute baseRadiusUV. */
        private const val REF_RADIUS_CM = 0.25f

        /** Physical snowflake radius: 3 mm diameter → 0.15 cm radius.
         *  Shader renders within sizeVar ∈ [0.80, 0.98] × sz, so visible
         *  diameter is ~3 mm typical, ≤ 6 mm with variation. */
        private const val FLAKE_RADIUS_CM = 0.15f

        /** Smoothing rate: how fast velocity rotates toward target (higher = snappier). */
        private const val SMOOTHING = 2.5f

        /** Below this tilt magnitude the snowflake keeps its current heading. */
        private const val DEAD_ZONE = 0.08f

        /** Margin past screen edge before respawn (in UV). */
        private const val EDGE_MARGIN = 0.04f
    }

    private class Flake(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var size: Float,
        var phase: Float          // rotation / variation seed, 0..2π
    )

    private var flakes: Array<Flake?> = arrayOfNulls(10)

    /** UV/s equivalent of [SPEED_CM_S], computed from [init]'s baseRadiusUV. */
    private var speedUV: Float = 0.22f

    /** UV radius for a snowflake, computed from [init]'s baseRadiusUV. */
    private var flakeRadiusUV: Float = 0.02f

    override fun init(baseRadiusUV: Float) {
        speedUV = SPEED_CM_S * baseRadiusUV / REF_RADIUS_CM
        flakeRadiusUV = FLAKE_RADIUS_CM * baseRadiusUV / REF_RADIUS_CM

        flakes = Array(10) {
            val size = flakeRadiusUV * (0.7f + Random.nextFloat() * 0.6f) // ±30% variation
            Flake(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                vx = 0f,
                vy = speedUV,
                size = size,
                phase = Random.nextFloat() * 6.283185f
            )
        }
    }

    // ax, ay: tilt acceleration in UV/s² (pre-scaled from m/s²)
    override fun update(dt: Float, ax: Float, ay: Float): Boolean {
        val mag = sqrt(ax * ax + ay * ay)

        for (i in flakes.indices) {
            val f = flakes[i]!!

            if (mag > DEAD_ZONE) {
                val invMag = 1f / mag
                val targetVx = ax * invMag * speedUV
                val targetVy = ay * invMag * speedUV

                val blend = (SMOOTHING * dt).coerceIn(0f, 1f)
                f.vx += (targetVx - f.vx) * blend
                f.vy += (targetVy - f.vy) * blend

                val curSpeed = sqrt(f.vx * f.vx + f.vy * f.vy)
                if (curSpeed > 0.0001f) {
                    val scale = speedUV / curSpeed
                    f.vx *= scale
                    f.vy *= scale
                }
            }

            f.x += f.vx * dt
            f.y += f.vy * dt

            val margin = f.size + EDGE_MARGIN
            if (f.x < -margin || f.x > 1f + margin || f.y < -margin || f.y > 1f + margin) {
                respawnAtTop(f, mag, ax, ay)
            }
        }
        return true
    }

    /** Place [f] along the edge that is "upwind" relative to gravity. */
    private fun respawnAtTop(f: Flake, mag: Float, ax: Float, ay: Float) {
        val t = Random.nextFloat()

        if (mag > DEAD_ZONE) {
            val invMag = 1f / mag
            val gx = ax * invMag
            val gy = ay * invMag

            if (abs(gy) > abs(gx)) {
                f.x = t
                f.y = if (gy > 0f) -f.size - EDGE_MARGIN else 1f + f.size + EDGE_MARGIN
            } else {
                f.x = if (gx > 0f) -f.size - EDGE_MARGIN else 1f + f.size + EDGE_MARGIN
                f.y = t
            }

            f.vx = gx * speedUV
            f.vy = gy * speedUV
        } else {
            f.x = t
            f.y = -f.size - EDGE_MARGIN
            f.vx = 0f
            f.vy = speedUV
        }

        f.size = flakeRadiusUV * (0.7f + Random.nextFloat() * 0.6f)
        f.phase = Random.nextFloat() * 6.283185f
    }

    override fun fillDropArray(arr: FloatArray) {
        for (i in flakes.indices) {
            val f = flakes[i]!!
            arr[i * 4] = f.x
            arr[i * 4 + 1] = f.y
            arr[i * 4 + 2] = f.size
            arr[i * 4 + 3] = f.phase
        }
    }

    override fun fillVelArray(arr: FloatArray) {
        for (i in flakes.indices) {
            val f = flakes[i]!!
            val curSpeed = sqrt(f.vx * f.vx + f.vy * f.vy)
            val invSpeed = if (curSpeed > 0.0001f) 1f / curSpeed else 0f
            arr[i * 4] = f.vx * invSpeed
            arr[i * 4 + 1] = f.vy * invSpeed
            arr[i * 4 + 2] = 0f
            arr[i * 4 + 3] = 0f
        }
    }
}
