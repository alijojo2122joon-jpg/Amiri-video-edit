package com.amiri.cut.core.text

import com.amiri.cut.core.model.Param
import com.amiri.cut.core.model.Props
import com.amiri.cut.core.model.TextSpec

/** Ready-made text looks: style values + animations + optional liquid glass. */
object TextPresets {
    class Preset(
        val name: String,
        val values: Map<String, Float>,
        val animIn: String = "None", val animOut: String = "None", val animLoop: String = "None",
        val glass: String = "None", val glassIn: String = "None", val glassOut: String = "None",
        val bold: Boolean? = null,
    )

    val ALL = listOf(
        Preset("Liquid Pill", mapOf("size" to 0.05f), "letterRise", "fade", glass = "Liquid", glassIn = "Liquid", glassOut = "Fade", bold = true),
        Preset("Frosted Card", mapOf("size" to 0.045f), "wordRise", "wordFade", glass = "Card", glassIn = "Expand", glassOut = "Blur In"),
        Preset("Dark Glass", mapOf("size" to 0.05f, "cr" to 1f, "cg" to 0.95f, "cb" to 0.85f), "fade", "fade", glass = "Dark", glassIn = "Pop", glassOut = "Pop"),
        Preset("Bubble", mapOf("size" to 0.06f), "letterPop", "letterPop", glass = "Bubble", glassIn = "Morph", glassOut = "Morph", bold = true),
        Preset("Neon", mapOf("cr" to 0.85f, "cg" to 1f, "cb" to 1f, "glow" to 1f, "glowRadius" to 0.5f, "gr" to 0.1f, "gg" to 0.85f, "gb" to 1f), "glitch", "glitch", "flicker"),
        Preset("Cinema Title", mapOf("size" to 0.07f, "tracking" to 0.35f, "shadowA" to 0.6f, "shadowBlur" to 0.5f, "shadowDist" to 0f), "tracking", "blur"),
        Preset("Subtitle", mapOf("size" to 0.038f, "bgA" to 0.55f, "radius" to 0.3f, "padding" to 0.35f), "fade", "fade"),
        Preset("Bold Pop", mapOf("size" to 0.09f, "strokeW" to 0.08f, "cr" to 1f, "cg" to 0.85f, "cb" to 0.2f), "pop", "zoomIn", "pulse", bold = true),
        Preset("Typewriter", mapOf("size" to 0.045f), "typewriter", "fade"),
        Preset("Wave", mapOf("size" to 0.06f, "glow" to 0.4f), "letterBounce", "letterDrop", "wave", bold = true),
        Preset("Outline", mapOf("size" to 0.08f, "strokeW" to 0.06f, "opacity" to 1f, "cr" to 0f, "cg" to 0f, "cb" to 0f, "sr" to 1f, "sg" to 1f, "sb" to 1f), "lineReveal", "lineReveal", bold = true),
        Preset("Gold", mapOf("cr" to 1f, "cg" to 0.8f, "cb" to 0.35f, "glow" to 0.5f, "glowRadius" to 0.25f, "gr" to 1f, "gg" to 0.65f, "gb" to 0.2f, "shadowA" to 0.5f), "letterBlur", "fade", "float"),
        Preset("Deep Shadow", mapOf("size" to 0.08f, "shadowA" to 0.85f, "shadowDist" to 0.12f, "shadowBlur" to 0.15f), "slideUp", "slideDown", bold = true),
        Preset("Minimal", mapOf("size" to 0.035f, "tracking" to 0.2f), "wordFade", "wordFade"),
        Preset("Glitch", mapOf("size" to 0.07f, "cr" to 1f, "cg" to 0.25f, "cb" to 0.45f), "glitch", "glitch", "shake", bold = true),
        Preset("Spin", mapOf("size" to 0.06f), "letterSpin", "scatter"),
        Preset("Elastic", mapOf("size" to 0.07f), "elastic", "zoomOut", "jelly", bold = true),
        Preset("Ice Glass", mapOf("size" to 0.05f, "cr" to 0.1f, "cg" to 0.2f, "cb" to 0.35f), "wordBlur", "wordBlur", glass = "Ice", glassIn = "Stretch Up", glassOut = "Fade"),
        Preset("Sunset Glass", mapOf("size" to 0.05f), "letterFade", "fade", "float", glass = "Sunset", glassIn = "Slide Up", glassOut = "Drop"),
        Preset("Night Glass", mapOf("size" to 0.05f), "lineSlide", "lineFade", glass = "Night Blue", glassIn = "Ripple", glassOut = "Fade"),
    )

    /** Applies a preset on top of the current text (keeps the words and font). */
    fun apply(spec: TextSpec, p: Preset): TextSpec {
        var props = spec.props
        val gs = Glass.style(p.glass)
        if (p.glass != "None") {
            for (prm in Glass.PARAMS) props = props.with(prm.id, Param(gs?.get(prm.id) ?: prm.default))
            for (k in listOf("gtr", "gtg", "gtb")) props = props.with(k, Param(gs?.get(k) ?: 1f))
        }
        for ((k, v) in p.values) props = props.with(k, Param(v))
        return spec.copy(
            props = props, animIn = p.animIn, animOut = p.animOut, animLoop = p.animLoop,
            glass = p.glass, glassIn = p.glassIn, glassOut = p.glassOut, bold = p.bold ?: spec.bold,
        )
    }

    /** Sets a glass style (or "None") with its values. */
    fun withGlass(spec: TextSpec, style: String): TextSpec {
        if (style == "None") return spec.copy(glass = "None")
        val gs = Glass.style(style) ?: emptyMap()
        var props: Props = spec.props
        for (prm in Glass.PARAMS) props = props.with(prm.id, Param(gs[prm.id] ?: prm.default))
        for (k in listOf("gtr", "gtg", "gtb")) props = props.with(k, Param(gs[k] ?: 1f))
        return spec.copy(props = props, glass = style)
    }
}
