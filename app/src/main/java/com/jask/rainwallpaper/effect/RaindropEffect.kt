package com.jask.rainwallpaper.effect

/**
 * Rain-on-glass effect: a faithful port of the ShaderToy raindrop shader from
 * `index.html`. The entire effect is procedural and time-driven — there is no
 * per-drop physics. The paired [RaindropSimulation] exists only to keep the
 * render loop alive (it always reports "animating" so the engine never idles).
 *
 * GLSL porting notes (ShaderToy/GL → OpenGL ES 2.0):
 *   - `vUv`            → `vTexCoord`        (engine-provided varying)
 *   - `iTime`          → `uTime`            (engine-provided, accumulated dt)
 *   - `iResolution`    → `uResolution`      (engine-provided, surface pixels)
 *   - `iChannel0`      → `uTexture`         (background wallpaper bitmap)
 *   - `texture(...)`   → `texture2D(...)`
 *   - The dual Gaussian-blur loop is rewritten with constant integer bounds
 *     (`for (int d = 0; d < DIRECTIONS; d++)`) so it is legal under the
 *     GLES 2.0 loop restrictions (constant bounds + constant step).
 *   - Texture sampling is routed through the engine crop transform
 *     (`UV * uCropScale + uCropOffset`) so the rain overlays the wallpaper
 *     background exactly like the pass-through / other effects.
 */
class RaindropEffect : GLEffect {
    override val key = "raindrop"
    override val label = "Raindrop Glass"
    override val targetFps = 30
    override val usesSensors = false
    override val usesSimulationUniforms = false
    override val isTimeDriven = true

    override val fragmentShader = """
        precision highp float;

        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uCropOffset;
        uniform vec2 uCropScale;
        uniform vec2 uResolution;
        uniform float uTime;

        #define S(a, b, t) smoothstep(a, b, t)
        #define size 0.2

        vec3 N13(float p) {
            vec3 p3 = fract(vec3(p) * vec3(.1031, .11369, .13787));
            p3 += dot(p3, p3.yzx + 19.19);
            return fract(vec3((p3.x + p3.y) * p3.z, (p3.x + p3.z) * p3.y, (p3.y + p3.z) * p3.x));
        }

        vec4 N14(float t) {
            return fract(sin(t * vec4(123., 1024., 1456., 264.)) * vec4(6547., 345., 8799., 1564.));
        }
        float N(float t) {
            return fract(sin(t * 12345.564) * 7658.76);
        }

        float Saw(float b, float t) {
            return S(0., b, t) * S(1., b, t);
        }

        vec2 Drops(vec2 uv, float t) {
            vec2 UV = uv;
            uv.y += t * 0.8;
            vec2 a = vec2(6., 1.);
            vec2 grid = a * 2.;
            vec2 id = floor(uv * grid);
            float colShift = N(id.x);
            uv.y += colShift;
            id = floor(uv * grid);
            vec3 n = N13(id.x * 35.2 + id.y * 2376.1);
            vec2 st = fract(uv * grid) - vec2(.5, 0);
            float x = n.x - .5;
            float y = UV.y * 20.;
            float distort = sin(y + sin(y));
            x += distort * (.5 - abs(x)) * (n.z - .5);
            x *= .7;
            float ti = fract(t + n.z);
            y = (Saw(.85, ti) - .5) * .9 + .5;
            vec2 p = vec2(x, y);
            float d = length((st - p) * a.yx);
            float dSize = size;
            float Drop = S(dSize, .0, d);
            float r = sqrt(S(1., y, st.y));
            float cd = abs(st.x - x);
            float trail = S((dSize * .5 + .03) * r, (dSize * .5 - .05) * r, cd);
            float trailFront = S(-.02, .02, st.y - y);
            trail *= trailFront;
            y = UV.y;
            y += N(id.x);
            float trail2 = S(dSize * r, .0, cd);
            float droplets = max(0., (sin(y * (1. - y) * 120.) - st.y)) * trail2 * trailFront * n.z;
            y = fract(y * 10.) + (st.y - .5);
            float dd = length(st - vec2(x, y));
            droplets = S(dSize * N(id.x), 0., dd);
            float m = Drop + droplets * r * trailFront;
            return vec2(m, trail);
        }

        float StaticDrops(vec2 uv, float t) {
            uv *= 30.;
            vec2 id = floor(uv);
            uv = fract(uv) - .5;
            vec3 n = N13(id.x * 107.45 + id.y * 3543.654);
            vec2 p = (n.xy - .5) * 0.5;
            float d = length(uv - p);
            float fade = Saw(.025, fract(t + n.z));
            float c = S(size, 0., d) * fract(n.z * 10.) * fade;
            return c;
        }

        vec2 Rain(vec2 uv, float t) {
            float s = StaticDrops(uv, t);
            vec2 r1 = Drops(uv, t);
            vec2 r2 = Drops(uv * 1.8, t);
            float c = s + r1.x + r2.x;
            c = S(.3, 1., c);
            return vec2(c, max(r1.y, r2.y));
        }

        void main() {
            vec2 fragCoord = vTexCoord * uResolution;
            // vTexCoord.y is top-down (image space); ShaderToy's fragCoord.y is
            // bottom-up. Flip y so the procedural rain coordinates match the
            // original and drops fall downward.
            vec2 uv = (fragCoord.xy - .5 * uResolution.xy) / uResolution.y;
            uv.y = -uv.y;
            vec2 UV = vTexCoord;
            float T = uTime;

            float t = T * .2;

            // Slight inward zoom, then map through the wallpaper crop transform
            // so the rain distorts the same background pixels the engine draws.
            UV = (UV - .5) * (.9) + .5;
            vec2 imageUV = UV * uCropScale + uCropOffset;

            vec2 c = Rain(uv, t);

            vec2 e = vec2(.001, 0.);
            float cx = Rain(uv + e, t).x;
            float cy = Rain(uv + e.yx, t).x;
            vec2 n = vec2(cx - c.x, cy - c.x);

            // ---- Gaussian blur (ported to ES2.0-compliant constant loops) ----
            // Eight compile-time directions avoid evaluating sin/cos for every
            // fragment. Four radial taps keep the glass blur while reducing the
            // background reads from 256 to 32 per pixel.
            const int DIRECTIONS = 8;
            const int QUALITY = 4;
            const float BLUR_SIZE = 20.0;
            vec2 radius = BLUR_SIZE / uResolution.xy;
            const mat2 ROTATE_45 = mat2(
                0.70710678, 0.70710678,
                -0.70710678, 0.70710678
            );
            vec2 direction = vec2(1.0, 0.0);
            vec3 col = texture2D(uTexture, imageUV).rgb;

            for (int d = 0; d < DIRECTIONS; d++) {
                for (int i = 1; i <= QUALITY; i++) {
                    float fi = float(i) / float(QUALITY);
                    col += texture2D(uTexture, imageUV + n + direction * radius * fi).rgb;
                }
                direction = ROTATE_45 * direction;
            }

            col /= float(QUALITY * DIRECTIONS + 1);

            vec3 tex = texture2D(uTexture, imageUV + n).rgb;
            c.y = clamp(c.y, 0.0, 1.0);

            col -= c.y;
            col += c.y * (tex + .6);

            gl_FragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    override fun createSimulation(): Simulation? = null
}
