package com.jask.rainwallpaper.effect

/**
 * A GL-based visual effect: a fragment shader paired with a physics simulation.
 *
 * The render engine queries [fragmentShader] and [createSimulation] — it never
 * knows which concrete effect is active.
 */
interface GLEffect {
    val key: String
    val label: String

    /** GLSL fragment shader source (precision, uniforms, main). */
    val fragmentShader: String

    /** Maximum render cadence for this effect. */
    val targetFps: Int
        get() = 60

    /** Whether this effect reacts to accelerometer input. */
    val usesSensors: Boolean
        get() = true

    /** Whether per-element simulation arrays must be uploaded every frame. */
    val usesSimulationUniforms: Boolean
        get() = true

    /** Whether time alone continuously changes the rendered output. */
    val isTimeDriven: Boolean
        get() = false

    /** Create a fresh simulation instance, or null for a shader-only effect. */
    fun createSimulation(): Simulation?
}

/**
 * Physics simulation: updated each frame by the render thread, writes
 * per-element data into float arrays that are uploaded to the shader.
 *
 * Layout contract (shared between simulation and its paired shader):
 *   dropArray[i*4+0] = element centre x    (UV)
 *   dropArray[i*4+1] = element centre y    (UV)
 *   dropArray[i*4+2] = element radius      (UV)
 *   dropArray[i*4+3] = element deformation (0..1)
 *
 *   velArray[i*4+0] = normalised velocity x
 *   velArray[i*4+1] = normalised velocity y
 *   velArray[i*4+2] = deformation (0..1)
 *   velArray[i*4+3] = 0 (unused)
 */
interface Simulation {
    fun init(baseRadiusUV: Float)
    fun update(dt: Float, ax: Float, ay: Float): Boolean
    fun fillDropArray(arr: FloatArray)
    fun fillVelArray(arr: FloatArray)
}
