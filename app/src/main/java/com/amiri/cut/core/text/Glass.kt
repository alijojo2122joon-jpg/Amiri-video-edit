package com.amiri.cut.core.text

import com.amiri.cut.core.effects.ParamSpec
import com.amiri.cut.core.model.TextSpec

/** Liquid-glass background behind text: parameters (animatable, in TextSpec.props), styles and animations. */
object Glass {
    val PARAMS = listOf(
        ParamSpec("gBlur", "Frost (blur)", 0f, 1f, 0.45f),
        ParamSpec("gRefract", "Liquid refraction", 0f, 1f, 0.55f),
        ParamSpec("gChroma", "Edge rainbow", 0f, 1f, 0.25f),
        ParamSpec("gRim", "Rim light", 0f, 1f, 0.6f),
        ParamSpec("gGloss", "Gloss", 0f, 1f, 0.35f),
        ParamSpec("gTintA", "Tint", 0f, 1f, 0.12f),
        ParamSpec("gSat", "Vibrancy", 0f, 1f, 0.3f),
        ParamSpec("gBright", "Brightness", -1f, 1f, 0.08f),
        ParamSpec("gShadow", "Shadow", 0f, 1f, 0.3f),
        ParamSpec("gRadius", "Roundness", 0f, 1f, 1f),
        ParamSpec("gPadX", "Padding X", 0f, 3f, 0.7f),
        ParamSpec("gPadY", "Padding Y", 0f, 2f, 0.38f),
        ParamSpec("gOpacity", "Glass opacity", 0f, 1f, 1f),
    )
    val TINT = Triple("gt", "Glass tint", floatArrayOf(1f, 1f, 1f))

    fun def(id: String): Float {
        PARAMS.firstOrNull { it.id == id }?.let { return it.default }
        return when (id) { "gtr", "gtg", "gtb" -> 1f; else -> 0f }
    }

    /** Style presets: param values (anything not listed uses the default). */
    val STYLES: List<Pair<String, Map<String, Float>>> = listOf(
        "Liquid" to mapOf(),
        "Clear" to mapOf("gBlur" to 0.12f, "gRefract" to 0.7f, "gTintA" to 0.04f, "gSat" to 0.15f, "gGloss" to 0.25f, "gShadow" to 0.15f),
        "Frosted" to mapOf("gBlur" to 0.85f, "gRefract" to 0.3f, "gTintA" to 0.22f, "gBright" to 0.15f, "gChroma" to 0.1f),
        "Dark" to mapOf("gBlur" to 0.6f, "gTintA" to 0.45f, "gtr" to 0.05f, "gtg" to 0.06f, "gtb" to 0.08f, "gBright" to -0.25f, "gRim" to 0.45f),
        "Bubble" to mapOf("gBlur" to 0.2f, "gRefract" to 1f, "gChroma" to 0.7f, "gRim" to 0.9f, "gGloss" to 0.6f, "gTintA" to 0.05f),
        "Ice" to mapOf("gBlur" to 0.7f, "gTintA" to 0.25f, "gtr" to 0.75f, "gtg" to 0.9f, "gtb" to 1f, "gBright" to 0.2f, "gRim" to 0.8f),
        "Mint" to mapOf("gBlur" to 0.55f, "gTintA" to 0.28f, "gtr" to 0.55f, "gtg" to 0.95f, "gtb" to 0.7f, "gSat" to 0.5f),
        "Sunset" to mapOf("gBlur" to 0.55f, "gTintA" to 0.3f, "gtr" to 1f, "gtg" to 0.62f, "gtb" to 0.45f, "gSat" to 0.5f),
        "Night Blue" to mapOf("gBlur" to 0.65f, "gTintA" to 0.4f, "gtr" to 0.15f, "gtg" to 0.3f, "gtb" to 0.75f, "gBright" to -0.15f, "gRim" to 0.85f),
        "Card" to mapOf("gRadius" to 0.35f, "gPadX" to 0.9f, "gPadY" to 0.6f, "gBlur" to 0.6f),
        "Soft Square" to mapOf("gRadius" to 0.2f, "gPadX" to 0.6f, "gPadY" to 0.5f, "gRefract" to 0.4f),
    )
    fun style(name: String?): Map<String, Float>? = STYLES.firstOrNull { it.first == name }?.second

    /** Glass entrance/exit animations (exits play backwards). */
    val ANIMS = listOf("None", "Fade", "Pop", "Liquid", "Expand", "Stretch Up", "Blur In", "Slide Up", "Drop", "Morph", "Ripple")

    /** Animation state of the glass panel. */
    class State { var alpha = 1f; var sx = 1f; var sy = 1f; var dy = 0f; var blur = 1f; var radius = 1f; var wobble = 0f }

    fun state(s: TextSpec, localUs: Long, durUs: Long, out: State = State()): State {
        out.alpha = 1f; out.sx = 1f; out.sy = 1f; out.dy = 0f; out.blur = 1f; out.radius = 1f; out.wobble = 0f
        val t = localUs / 1_000_000f; val d = durUs / 1_000_000f
        if (s.glassIn != "None") apply(s.glassIn, (t / s.glassInDur.coerceIn(0.05f, d / 2f)).coerceIn(0f, 1f), out)
        if (s.glassOut != "None") {
            val od = s.glassOutDur.coerceIn(0.05f, d / 2f)
            apply(s.glassOut, 1f - ((t - (d - od)) / od).coerceIn(0f, 1f), out)
        }
        return out
    }

    private fun apply(id: String, u: Float, o: State) {
        if (u >= 1f) return
        val e = Ease.outCubic(u)
        when (id) {
            "Fade" -> o.alpha *= e
            "Pop" -> { o.alpha *= (u * 3f).coerceAtMost(1f); val s = Ease.outBack(u).coerceAtLeast(0f); o.sx *= s; o.sy *= s }
            "Liquid" -> {
                o.alpha *= (u * 3f).coerceAtMost(1f)
                val s = Ease.outElastic(u).coerceAtLeast(0f)
                o.sx *= s * (1f + (1f - u) * 0.25f); o.sy *= s * (1f - (1f - u) * 0.2f); o.wobble = 1f - e
            }
            "Expand" -> { o.alpha *= (u * 4f).coerceAtMost(1f); o.sx *= (0.05f + 0.95f * Ease.inOutCubic(u)); o.radius = 1f }
            "Stretch Up" -> { o.alpha *= (u * 3f).coerceAtMost(1f); o.sy *= 0.05f + 0.95f * Ease.outBack(u).coerceAtLeast(0f) }
            "Blur In" -> { o.alpha *= e; o.blur *= e }
            "Slide Up" -> { o.alpha *= e; o.dy += (1f - e) * 1.2f }
            "Drop" -> { o.alpha *= (u * 4f).coerceAtMost(1f); o.dy -= (1f - Ease.outBounce(u)) * 1.6f }
            "Morph" -> { o.alpha *= e; val s = 0.6f + 0.4f * Ease.outBack(u); o.sx *= s; o.sy *= s; o.radius = 1f + (1f - e) * 2f }
            "Ripple" -> { o.alpha *= e; o.wobble = (1f - u) * 1.5f; val s = 0.85f + 0.15f * e; o.sx *= s; o.sy *= s }
        }
    }
}
