package com.jask.rainwallpaper.effect

/**
 * Central registry of all available effects.
 *
 * To add a new effect:
 *   1. Implement [GLEffect] (and [Simulation] if it has physics).
 *   2. Add it to [all] below.
 */
object Effects {

    /** A selectable entry in the UI — key + display label. */
    data class Choice(val key: String, val label: String)

    /** Sentinel for "no GL effect" — the engine falls back to the pass-through shader. */
    val NONE: Nothing? = null

    val GRAVITY_BUBBLE = GravityBubbleEffect()
    val SNOWFALL = SnowfallEffect()
    val RAINDROP = RaindropEffect()

    /** Every available GL effect (excluding the none sentinel). */
    fun all(): List<GLEffect> = listOf(GRAVITY_BUBBLE, SNOWFALL, RAINDROP)

    /** Look up an effect by its persisted [key]. Returns null when the key is unknown. */
    fun fromKey(key: String): GLEffect? = all().firstOrNull { it.key == key }

    /** Effect choices for UI display — includes the "none" entry. */
    fun uiChoices(): List<Choice> =
        listOf(Choice("none", "None")) + all().map { Choice(it.key, it.label) }
}
