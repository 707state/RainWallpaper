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

    /** Create a fresh simulation instance for this effect. */
    fun createSimulation(): Simulation
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
