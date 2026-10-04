package com.amiri.cut.core.effects

/**
 * Single source of truth for every adjustable parameter in the app. The UI builds
 * its sliders from these specs and the renderer reads values by the same ids, so a
 * control can never exist without a real implementation behind it.
 */
data class ParamSpec(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    /** Display multiplier and unit (e.g. 100 + "%"). */
    val displayScale: Float = 100f,
    val unit: String = "%",
    val decimals: Int = 0,
) {
    fun format(v: Float): String =
        if (decimals == 0) "${Math.round(v * displayScale)}$unit" else "%.${decimals}f$unit".format(v * displayScale)
}

enum class EffectCategory(val label: String) { LIGHT("Light"), MOTION("Motion"), FILM("Film"), BLUR("Blur"), DISTORTION("Distortion"), COLOR("Color"), STYLIZE("Stylize") }

data class OptionSpec(val id: String, val label: String, val choices: List<String>, val default: String)

data class EffectSpec(
    val type: String,
    val label: String,
    val category: EffectCategory,
    val params: List<ParamSpec>,
    val options: List<OptionSpec> = emptyList(),
    /** Groups of three param ids that form an RGB color (for color pickers). */
    val colors: List<Triple<String, String, String>> = emptyList(),
    val description: String = "",
    /** Kept for old projects but not offered in the Add-effect list. */
    val hidden: Boolean = false,
    /** Affects the layer's motion (transform) rather than its pixels. */
    val motion: Boolean = false,
) {
    fun param(id: String): ParamSpec? = params.firstOrNull { it.id == id }
}

private fun p(id: String, label: String, min: Float, max: Float, def: Float, scale: Float = 100f, unit: String = "%", dec: Int = 0) =
    ParamSpec(id, label, min, max, def, scale, unit, dec)

private fun deg(id: String, label: String, min: Float, max: Float, def: Float) = ParamSpec(id, label, min, max, def, 1f, "°")

private fun rgb(prefix: String, label: String, r: Float, g: Float, b: Float) = listOf(
    p("${prefix}r", "$label R", 0f, 1f, r), p("${prefix}g", "$label G", 0f, 1f, g), p("${prefix}b", "$label B", 0f, 1f, b),
)

val BLEND_CHOICES = listOf("Screen", "Add", "Normal", "Overlay", "Soft Light")

object EffectCatalog {

    val HSL_RANGES = listOf("red", "yellow", "green", "cyan", "blue", "magenta")

    val COLOR = EffectSpec(
        "color", "Color Correction", EffectCategory.COLOR,
        params = listOf(
            p("exposure", "Exposure", -3f, 3f, 0f, 1f, " EV", 2),
            p("contrast", "Contrast", -1f, 1f, 0f),
            p("brightness", "Brightness", -1f, 1f, 0f),
            p("highlights", "Highlights", -1f, 1f, 0f),
            p("shadows", "Shadows", -1f, 1f, 0f),
            p("whites", "Whites", -1f, 1f, 0f),
            p("blacks", "Blacks", -1f, 1f, 0f),
            p("saturation", "Saturation", -1f, 1f, 0f),
            p("temperature", "Temperature", -1f, 1f, 0f),
            p("tint", "Tint", -1f, 1f, 0f),
        ) + HSL_RANGES.flatMap { r ->
            listOf(p("hsl_${r}_h", "Hue", -1f, 1f, 0f), p("hsl_${r}_s", "Saturation", -1f, 1f, 0f), p("hsl_${r}_l", "Luminance", -1f, 1f, 0f))
        } + listOf("lift", "gamma", "gain").flatMap { w ->
            listOf(p("${w}_x", "X", -1f, 1f, 0f), p("${w}_y", "Y", -1f, 1f, 0f), p("${w}_l", "Level", -1f, 1f, 0f))
        } + listOf(
            p("vignette", "Vignette", 0f, 1f, 0f),
            p("vig_feather", "Vignette feather", 0.05f, 1f, 0.5f),
            p("sharpen", "Sharpen", 0f, 1f, 0f),
            p("blur", "Blur", 0f, 1f, 0f),
            p("vibrance", "Vibrance", -1f, 1f, 0f),
            p("fade", "Fade (matte)", 0f, 1f, 0f),
            p("clarity", "Clarity", -1f, 1f, 0f),
            p("dehaze", "Dehaze", -1f, 1f, 0f),
            p("sh_hue", "Shadows hue", 0f, 1f, 0.55f, 360f, "°"),
            p("sh_sat", "Shadows tint", 0f, 1f, 0f),
            p("hi_hue", "Highlights hue", 0f, 1f, 0.1f, 360f, "°"),
            p("hi_sat", "Highlights tint", 0f, 1f, 0f),
            p("split_bal", "Balance", -1f, 1f, 0f),
            p("lookAmt", "Look intensity", 0f, 1.5f, 1f),
        ),
        description = "Basic, HSL, curves, color wheels, vignette, sharpen, blur",
    )

    val LUT = EffectSpec(
        "lut", "LUT (.cube)", EffectCategory.COLOR,
        params = listOf(p("strength", "Strength", 0f, 1f, 1f)),
        description = "3D lookup table imported from storage",
    )

    val CHROMA = EffectSpec(
        "chroma", "Chroma Key", EffectCategory.COLOR,
        params = rgb("k", "Key", 0.1f, 0.85f, 0.2f) + listOf(
            p("tolerance", "Tolerance", 0f, 1f, 0.3f),
            p("softness", "Softness", 0f, 1f, 0.15f),
            p("spill", "Spill reduction", 0f, 1f, 0.6f),
            p("edge", "Edge feather", 0f, 1f, 0.2f),
        ),
        colors = listOf(Triple("kr", "kg", "kb")),
        description = "Green / blue screen removal",
    )

    val GLOW = EffectSpec(
        "glow", "Glow", EffectCategory.LIGHT,
        params = listOf(
            p("threshold", "Threshold", 0f, 1f, 0.6f),
            p("intensity", "Intensity", 0f, 4f, 1.2f),
            p("radius", "Radius", 0f, 1f, 0.35f),
            p("softness", "Softness", 0f, 1f, 0.5f),
        ) + rgb("c", "Color", 1f, 1f, 1f),
        options = listOf(OptionSpec("blend", "Blend", BLEND_CHOICES, "Screen")),
        colors = listOf(Triple("cr", "cg", "cb")),
    )

    val SWEEP = EffectSpec(
        "sweep", "Light Sweep (simple)", EffectCategory.LIGHT, hidden = true,
        params = listOf(
            p("pos", "Position", -0.5f, 1.5f, 0.5f),
            deg("angle", "Angle", -90f, 90f, 25f),
            p("width", "Width", 0.01f, 0.6f, 0.12f),
            p("intensity", "Intensity", 0f, 4f, 1.5f),
            p("softness", "Softness", 0f, 1f, 0.6f),
            p("opacity", "Opacity", 0f, 1f, 1f),
        ) + rgb("c", "Color", 1f, 0.97f, 0.9f),
        options = listOf(OptionSpec("blend", "Blend", BLEND_CHOICES, "Add")),
        colors = listOf(Triple("cr", "cg", "cb")),
        description = "Animate Position with keyframes to move the light across",
    )

    /** After Effects-style CC Light Sweep. */
    val CC_SWEEP = EffectSpec(
        "ccsweep", "CC Light Sweep", EffectCategory.LIGHT,
        params = listOf(
            p("cx", "Center X", -0.5f, 1.5f, 0.5f),
            p("cy", "Center Y", -0.5f, 1.5f, 0.5f),
            deg("direction", "Direction", -180f, 180f, -30f),
            p("width", "Width", 0.005f, 1f, 0.12f),
            p("intensity", "Sweep Intensity", 0f, 3f, 1f),
            p("edgeIntensity", "Edge Intensity", 0f, 3f, 1f),
            p("edgeThickness", "Edge Thickness", 0f, 1f, 0.25f),
        ) + rgb("c", "Light Color", 1f, 1f, 1f),
        options = listOf(
            OptionSpec("shape", "Shape", listOf("Linear", "Smooth", "Sharp"), "Smooth"),
            OptionSpec("reception", "Light Reception", listOf("Add", "Composite", "Cutout"), "Add"),
        ),
        colors = listOf(Triple("cr", "cg", "cb")),
        description = "Keyframe Center X/Y to sweep the light across the layer",
    )

    /** After Effects-style wiggle on the layer's position (and optionally rotation/scale). */
    val WIGGLE = EffectSpec(
        "wiggle", "Wiggle Position", EffectCategory.MOTION,
        params = listOf(
            p("freq", "Frequency", 0.1f, 15f, 2f, 1f, " /s", 1),
            p("amp", "Amplitude", 0f, 0.5f, 0.03f),
            deg("rotAmp", "Rotation", 0f, 45f, 0f),
            p("scaleAmp", "Scale", 0f, 0.5f, 0f),
            p("seed", "Seed", 0f, 100f, 7f, 1f, ""),
            p("detail", "Detail (octaves)", 0f, 1f, 0.5f),
        ),
        options = listOf(OptionSpec("axes", "Axes", listOf("X & Y", "X only", "Y only"), "X & Y")),
        motion = true,
        description = "Random smooth shake like the AE wiggle() expression",
    )

    val RAYS = EffectSpec(
        "rays", "Light Rays", EffectCategory.LIGHT,
        params = listOf(
            p("x", "Position X", 0f, 1f, 0.5f),
            p("y", "Position Y", 0f, 1f, 0.15f),
            deg("direction", "Direction", -180f, 180f, 0f),
            p("intensity", "Intensity", 0f, 4f, 1.2f),
            p("length", "Length", 0f, 1f, 0.5f),
            p("decay", "Decay", 0f, 1f, 0.85f),
            p("softness", "Softness", 0f, 1f, 0.5f),
            p("opacity", "Opacity", 0f, 1f, 1f),
        ) + rgb("c", "Color", 1f, 0.92f, 0.75f),
        colors = listOf(Triple("cr", "cg", "cb")),
    )

    val LEAK_PRESETS = listOf("Amber", "Rose", "Sunset", "Teal Burn", "Magenta", "Golden Hour", "Cold Flash", "Vintage Red")

    val LEAK = EffectSpec(
        "leak", "Light Leak", EffectCategory.LIGHT,
        params = listOf(
            p("intensity", "Intensity", 0f, 2f, 1f),
            p("opacity", "Opacity", 0f, 1f, 0.8f),
            p("x", "Position X", -0.5f, 1.5f, 0.5f),
            p("y", "Position Y", -0.5f, 1.5f, 0.5f),
            p("scale", "Scale", 0.3f, 3f, 1f),
            deg("rot", "Rotation", -180f, 180f, 0f),
            p("speed", "Motion", 0f, 2f, 0.5f),
        ),
        options = listOf(OptionSpec("preset", "Preset", LEAK_PRESETS, "Amber"), OptionSpec("blend", "Blend", BLEND_CHOICES, "Screen")),
    )

    val FILM_PRESETS = listOf("Classic Film", "Vintage", "Nostalgic", "Warm Film", "Cold Film", "Old Camera")

    val FILM = EffectSpec(
        "film", "Film Look", EffectCategory.FILM,
        params = listOf(
            p("grain", "Film grain", 0f, 1f, 0.35f),
            p("grainSize", "Grain size", 0.5f, 3f, 1f, 100f, "%"),
            p("dust", "Dust", 0f, 1f, 0.2f),
            p("scratch", "Scratch", 0f, 1f, 0.15f),
            p("flicker", "Flicker", 0f, 1f, 0.15f),
            p("vignette", "Vignette", 0f, 1f, 0.35f),
            p("halation", "Halation", 0f, 1f, 0.3f),
            p("fade", "Fade", 0f, 1f, 0.15f),
            p("chroma", "Chromatic aberration", 0f, 1f, 0.15f),
            p("blur", "Film blur", 0f, 1f, 0.1f),
            p("warmth", "Warmth", -1f, 1f, 0.15f),
        ),
        options = listOf(OptionSpec("preset", "Preset", FILM_PRESETS, "Classic Film")),
    )

    val FILM_PRESET_VALUES: Map<String, Map<String, Float>> = mapOf(
        "Classic Film" to mapOf("grain" to 0.35f, "grainSize" to 1f, "dust" to 0.15f, "scratch" to 0.1f, "flicker" to 0.1f, "vignette" to 0.35f, "halation" to 0.3f, "fade" to 0.12f, "chroma" to 0.1f, "blur" to 0.08f, "warmth" to 0.1f),
        "Vintage" to mapOf("grain" to 0.5f, "grainSize" to 1.4f, "dust" to 0.4f, "scratch" to 0.35f, "flicker" to 0.3f, "vignette" to 0.55f, "halation" to 0.25f, "fade" to 0.35f, "chroma" to 0.2f, "blur" to 0.2f, "warmth" to 0.35f),
        "Nostalgic" to mapOf("grain" to 0.3f, "grainSize" to 1.2f, "dust" to 0.1f, "scratch" to 0.05f, "flicker" to 0.12f, "vignette" to 0.4f, "halation" to 0.5f, "fade" to 0.3f, "chroma" to 0.15f, "blur" to 0.15f, "warmth" to 0.4f),
        "Warm Film" to mapOf("grain" to 0.25f, "grainSize" to 1f, "dust" to 0.05f, "scratch" to 0f, "flicker" to 0.05f, "vignette" to 0.3f, "halation" to 0.4f, "fade" to 0.1f, "chroma" to 0.08f, "blur" to 0.05f, "warmth" to 0.55f),
        "Cold Film" to mapOf("grain" to 0.3f, "grainSize" to 1f, "dust" to 0.05f, "scratch" to 0.05f, "flicker" to 0.05f, "vignette" to 0.35f, "halation" to 0.15f, "fade" to 0.15f, "chroma" to 0.1f, "blur" to 0.05f, "warmth" to -0.5f),
        "Old Camera" to mapOf("grain" to 0.7f, "grainSize" to 2f, "dust" to 0.6f, "scratch" to 0.6f, "flicker" to 0.55f, "vignette" to 0.7f, "halation" to 0.1f, "fade" to 0.45f, "chroma" to 0.35f, "blur" to 0.35f, "warmth" to 0.25f),
    )

    val BLUR = EffectSpec("blur", "Gaussian Blur", EffectCategory.BLUR, params = listOf(p("radius", "Radius", 0f, 1f, 0.25f)))
    val DIRBLUR = EffectSpec("dirblur", "Directional Blur", EffectCategory.BLUR, params = listOf(deg("angle", "Angle", -180f, 180f, 0f), p("length", "Length", 0f, 1f, 0.25f)))
    val ZOOMBLUR = EffectSpec("zoomblur", "Zoom Blur", EffectCategory.BLUR, params = listOf(p("x", "Center X", 0f, 1f, 0.5f), p("y", "Center Y", 0f, 1f, 0.5f), p("amount", "Amount", 0f, 1f, 0.3f)))

    val CHROMAB = EffectSpec("chromab", "Chromatic Aberration", EffectCategory.DISTORTION, params = listOf(p("amount", "Amount", 0f, 1f, 0.3f), deg("angle", "Angle", -180f, 180f, 0f)))
    val WAVE = EffectSpec(
        "wave", "Wave Warp", EffectCategory.DISTORTION,
        params = listOf(
            p("amp", "Wave height", 0f, 0.15f, 0.015f, 1000f, "‰"),
            p("freq", "Waves across", 0f, 40f, 8f, 1f, "", 1),
            deg("angle", "Direction", -180f, 180f, 0f),
            p("speed", "Wave speed", -5f, 5f, 1f, 1f, " /s", 1),
            deg("phase", "Phase", -360f, 360f, 0f),
        ),
        options = listOf(
            OptionSpec("type", "Wave type", listOf("Sine", "Square", "Triangle", "Sawtooth", "Circle", "Semicircle", "Noise"), "Sine"),
            OptionSpec("pin", "Pinning", listOf("None", "All edges", "Left & right", "Top & bottom"), "None"),
        ),
        description = "Ripples the layer with a travelling wave.",
    )

    val SABER = EffectSpec(
        "saber", "Saber", EffectCategory.LIGHT,
        params = listOf(
            p("glow", "Glow intensity", 0f, 4f, 1.2f, 100f, "%"),
            p("spread", "Glow spread", 0f, 1f, 0.35f),
            p("core", "Core size", 0f, 0.05f, 0.006f, 1000f, "‰", 1),
            p("coreBright", "Core brightness", 0f, 2f, 1f),
            p("flicker", "Flicker", 0f, 1f, 0f),
            p("flickerSpeed", "Flicker speed", 0f, 30f, 10f, 1f, " /s", 1),
            p("distort", "Distortion", 0f, 1f, 0f),
            p("distortSpeed", "Distortion speed", 0f, 5f, 1f, 1f, "", 1),
            p("start", "Start offset", 0f, 1f, 0f),
            p("end", "End offset", 0f, 1f, 1f),
            p("offset", "Offset (loop)", -1f, 1f, 0f),
            p("x1", "Line start X", -0.5f, 1.5f, 0.2f),
            p("y1", "Line start Y", -0.5f, 1.5f, 0.5f),
            p("x2", "Line end X", -0.5f, 1.5f, 0.8f),
            p("y2", "Line end Y", -0.5f, 1.5f, 0.5f),
        ) + rgb("c", "Glow color", 0.15f, 0.5f, 1f),
        options = listOf(
            OptionSpec("source", "Core source", listOf("Layer path", "Drawn path", "Line"), "Layer path"),
            OptionSpec("composite", "Composite", listOf("Add", "Saber only"), "Add"),
        ),
        colors = listOf(Triple("cr", "cg", "cb")),
        description = "Energy beam along the layer's pen path / shape outline / masks, or a straight line.",
    )
    val BULGE = EffectSpec("bulge", "Lens Bulge", EffectCategory.DISTORTION, params = listOf(p("amount", "Amount", -1f, 1f, 0.4f), p("x", "Center X", 0f, 1f, 0.5f), p("y", "Center Y", 0f, 1f, 0.5f), p("radius", "Radius", 0.05f, 1f, 0.4f)))

    val SHARPEN = EffectSpec("sharpen", "Sharpen", EffectCategory.STYLIZE, params = listOf(p("amount", "Amount", 0f, 2f, 0.6f)))
    val POSTERIZE = EffectSpec("posterize", "Posterize", EffectCategory.STYLIZE, params = listOf(p("levels", "Levels", 2f, 32f, 6f, 1f, "")))
    val MOSAIC = EffectSpec("mosaic", "Mosaic", EffectCategory.STYLIZE, params = listOf(p("size", "Cell size", 0.002f, 0.1f, 0.02f, 1000f, "‰")))
    val VIGNETTE = EffectSpec("vignette", "Vignette", EffectCategory.STYLIZE, params = listOf(p("amount", "Amount", 0f, 1f, 0.5f), p("feather", "Feather", 0.05f, 1f, 0.5f), p("roundness", "Roundness", 0f, 1f, 1f)))

    val ALL: List<EffectSpec> = listOf(SABER, GLOW, CC_SWEEP, SWEEP, WIGGLE, RAYS, LEAK, FILM, BLUR, DIRBLUR, ZOOMBLUR, CHROMAB, WAVE, BULGE, COLOR, LUT, CHROMA, SHARPEN, POSTERIZE, MOSAIC, VIGNETTE)

    fun spec(type: String): EffectSpec? = ALL.firstOrNull { it.type == type }
    fun byCategory(c: EffectCategory) = ALL.filter { it.category == c && !it.hidden }
}

object TransformSpec {
    val PARAMS = listOf(
        ParamSpec("px", "Position X", -1.5f, 1.5f, 0f),
        ParamSpec("py", "Position Y", -1.5f, 1.5f, 0f),
        ParamSpec("scale", "Scale", 0.05f, 5f, 1f),
        ParamSpec("rot", "Rotation", -720f, 720f, 0f, 1f, "°"),
        ParamSpec("ax", "Anchor X", 0f, 1f, 0.5f),
        ParamSpec("ay", "Anchor Y", 0f, 1f, 0.5f),
        ParamSpec("opacity", "Opacity", 0f, 1f, 1f),
        ParamSpec("cropL", "Crop left", 0f, 0.9f, 0f),
        ParamSpec("cropT", "Crop top", 0f, 0.9f, 0f),
        ParamSpec("cropR", "Crop right", 0f, 0.9f, 0f),
        ParamSpec("cropB", "Crop bottom", 0f, 0.9f, 0f),
    )
    val MOTION_BLUR = listOf(
        ParamSpec("mbAmount", "Motion blur", 0f, 1f, 0f),
        ParamSpec("mbShutter", "Shutter angle", 0f, 360f, 180f, 1f, "°"),
    )
    fun def(id: String): Float = (PARAMS + MOTION_BLUR).firstOrNull { it.id == id }?.default ?: 0f
}

object AudioSpec {
    val PARAMS = listOf(
        ParamSpec("volume", "Volume", 0f, 2f, 1f),
        ParamSpec("fadeIn", "Fade in", 0f, 10f, 0f, 1f, " s", 1),
        ParamSpec("fadeOut", "Fade out", 0f, 10f, 0f, 1f, " s", 1),
        ParamSpec("pan", "Pan (L / R)", -1f, 1f, 0f),
        ParamSpec("bass", "Bass", -12f, 12f, 0f, 1f, " dB", 1),
        ParamSpec("mid", "Voice / mid", -12f, 12f, 0f, 1f, " dB", 1),
        ParamSpec("treble", "Treble", -12f, 12f, 0f, 1f, " dB", 1),
    )
    fun def(id: String): Float = PARAMS.firstOrNull { it.id == id }?.default ?: 0f
}

object MaskSpec {
    val PARAMS = listOf(
        ParamSpec("x", "Position X", -0.5f, 1.5f, 0.5f),
        ParamSpec("y", "Position Y", -0.5f, 1.5f, 0.5f),
        ParamSpec("w", "Width", 0.01f, 2f, 0.5f),
        ParamSpec("h", "Height", 0.01f, 2f, 0.5f),
        ParamSpec("rot", "Rotation", -360f, 360f, 0f, 1f, "°"),
        ParamSpec("feather", "Feather", 0f, 0.5f, 0.03f),
        ParamSpec("expand", "Expansion", -0.3f, 0.3f, 0f),
        ParamSpec("opacity", "Opacity", 0f, 1f, 1f),
    )
    fun def(id: String): Float = PARAMS.firstOrNull { it.id == id }?.default ?: 0f
}

object TextSpecDefaults {
    val STYLE = listOf(
        ParamSpec("size", "Font size", 0.01f, 0.5f, 0.08f),
        ParamSpec("opacity", "Text opacity", 0f, 1f, 1f),
        ParamSpec("tracking", "Tracking", -0.2f, 1f, 0f),
        ParamSpec("lineHeight", "Line height", 0.6f, 3f, 1.15f),
        ParamSpec("strokeW", "Stroke width", 0f, 0.3f, 0f),
        ParamSpec("shadowA", "Shadow", 0f, 1f, 0f),
        ParamSpec("shadowBlur", "Shadow blur", 0f, 1f, 0.25f),
        ParamSpec("shadowDist", "Shadow distance", 0f, 1f, 0.08f),
        ParamSpec("shadowAngle", "Shadow angle", -180f, 180f, 45f, 1f, "°"),
        ParamSpec("glow", "Glow", 0f, 1f, 0f),
        ParamSpec("glowRadius", "Glow radius", 0f, 1f, 0.3f),
        ParamSpec("bgA", "Background", 0f, 1f, 0f),
        ParamSpec("radius", "Corner radius", 0f, 1f, 0.25f),
        ParamSpec("padding", "Padding", 0f, 1.5f, 0.35f),
    )
    /** RGB color groups: id prefix → (label, default). */
    val COLORS = listOf(
        Triple("c", "Color", floatArrayOf(1f, 1f, 1f)),
        Triple("s", "Stroke color", floatArrayOf(0f, 0f, 0f)),
        Triple("sh", "Shadow color", floatArrayOf(0f, 0f, 0f)),
        Triple("g", "Glow color", floatArrayOf(1f, 0.85f, 0.5f)),
        Triple("b", "Background color", floatArrayOf(0f, 0f, 0f)),
    )
    fun def(id: String): Float {
        STYLE.firstOrNull { it.id == id }?.let { return it.default }
        for ((pre, _, d) in COLORS) {
            when (id) { "${pre}r" -> return d[0]; "${pre}g" -> return d[1]; "${pre}b" -> return d[2] }
        }
        return 0f
    }
}

object ShapeSpecDefaults {
    val PARAMS = listOf(
        ParamSpec("w", "Width", 0.01f, 2f, 0.4f),
        ParamSpec("h", "Height", 0.01f, 2f, 0.4f),
        ParamSpec("radius", "Corner radius", 0f, 0.5f, 0f),
        ParamSpec("sides", "Sides / points", 3f, 16f, 5f, 1f, ""),
        ParamSpec("inner", "Star inner radius", 0.1f, 0.95f, 0.45f),
        ParamSpec("fillA", "Fill opacity", 0f, 1f, 1f),
        ParamSpec("strokeW", "Stroke width", 0f, 0.1f, 0f, 1000f, "‰"),
        ParamSpec("strokeA", "Stroke opacity", 0f, 1f, 1f),
        ParamSpec("trimStart", "Trim start", 0f, 1f, 0f),
        ParamSpec("trimEnd", "Trim end", 0f, 1f, 1f),
        ParamSpec("trimOffset", "Trim offset", -1f, 1f, 0f),
    )
    val COLORS = listOf(
        Triple("f", "Fill color", floatArrayOf(1f, 1f, 1f)),
        Triple("s", "Stroke color", floatArrayOf(0f, 0f, 0f)),
    )
    fun def(id: String): Float {
        PARAMS.firstOrNull { it.id == id }?.let { return it.default }
        for ((pre, _, d) in COLORS) when (id) { "${pre}r" -> return d[0]; "${pre}g" -> return d[1]; "${pre}b" -> return d[2] }
        return 0f
    }
}

/** One transition type. [index] selects the branch in Shaders.TRANSITION. */
data class TransitionSpec(val id: String, val label: String, val index: Int, val directional: Boolean = false, val soft: Boolean = false, val group: String)

/** 20 classic cinematic transitions, all rendered on the GPU (same in preview and export). */
object TransitionCatalog {
    val ALL = listOf(
        TransitionSpec("dissolve", "Cross Dissolve", 0, group = "Dissolve"),
        TransitionSpec("dipblack", "Dip to Black", 1, group = "Dissolve"),
        TransitionSpec("dipwhite", "Dip to White", 2, group = "Dissolve"),
        TransitionSpec("blurdissolve", "Blur Dissolve", 3, group = "Dissolve"),
        TransitionSpec("luma", "Luma Fade", 4, soft = true, group = "Dissolve"),
        TransitionSpec("ink", "Ink / Smoke", 5, soft = true, group = "Dissolve"),
        TransitionSpec("filmburn", "Film Burn", 6, group = "Light"),
        TransitionSpec("flash", "Flash", 7, group = "Light"),
        TransitionSpec("lightleak", "Light Leak", 8, group = "Light"),
        TransitionSpec("whip", "Whip Pan", 9, directional = true, group = "Motion"),
        TransitionSpec("push", "Push", 10, directional = true, group = "Motion"),
        TransitionSpec("slide", "Slide", 11, directional = true, group = "Motion"),
        TransitionSpec("zoomin", "Zoom In", 12, group = "Motion"),
        TransitionSpec("zoomout", "Zoom Out", 13, group = "Motion"),
        TransitionSpec("spin", "Spin", 14, group = "Motion"),
        TransitionSpec("wipe", "Wipe", 15, directional = true, soft = true, group = "Wipe"),
        TransitionSpec("iris", "Iris Round", 16, soft = true, group = "Wipe"),
        TransitionSpec("clock", "Clock Wipe", 17, soft = true, group = "Wipe"),
        TransitionSpec("glitch", "Glitch", 18, group = "Stylize"),
        TransitionSpec("pixelate", "Pixelate", 19, group = "Stylize"),
        TransitionSpec("warp", "Lens Warp", 20, group = "Stylize"),
    )
    fun spec(id: String) = ALL.firstOrNull { it.id == id }
    val GROUPS = listOf("Dissolve", "Light", "Motion", "Wipe", "Stylize")
    val DIRS = listOf("Left", "Right", "Up", "Down")
}

/**
 * Color "looks": param offsets added on top of the user's own Color Correction values and
 * scaled by the look intensity (effect param "lookAmt").
 */
object ColorLooks {
    val LOOKS: List<Pair<String, Map<String, Float>>> = listOf(
        "Light Nostalgic" to mapOf(
            "exposure" to 0.08f, "contrast" to -0.2f, "highlights" to -0.22f, "shadows" to 0.2f,
            "fade" to 0.3f, "saturation" to -0.18f, "vibrance" to 0.06f, "temperature" to 0.3f, "tint" to -0.06f,
            "sh_hue" to 0.07f, "sh_sat" to 0.22f, "hi_hue" to 0.13f, "hi_sat" to 0.18f, "split_bal" to 0.1f,
            "clarity" to -0.18f, "vignette" to 0.18f, "gamma_l" to 0.05f,
        ),
        "Nostalgic Film" to mapOf(
            "contrast" to -0.1f, "fade" to 0.38f, "saturation" to -0.25f, "temperature" to 0.38f, "tint" to -0.12f,
            "sh_hue" to 0.45f, "sh_sat" to 0.2f, "hi_hue" to 0.11f, "hi_sat" to 0.25f, "vignette" to 0.3f, "clarity" to -0.1f,
        ),
        "Teal & Orange" to mapOf(
            "contrast" to 0.15f, "vibrance" to 0.2f, "sh_hue" to 0.52f, "sh_sat" to 0.4f, "hi_hue" to 0.08f, "hi_sat" to 0.35f,
            "hsl_blue_h" to -0.15f, "hsl_cyan_s" to 0.2f,
        ),
        "Cinematic Fade" to mapOf("fade" to 0.32f, "contrast" to 0.12f, "saturation" to -0.18f, "sh_hue" to 0.55f, "sh_sat" to 0.2f, "vignette" to 0.2f),
        "Blockbuster" to mapOf(
            "contrast" to 0.28f, "clarity" to 0.22f, "dehaze" to 0.15f, "sh_hue" to 0.52f, "sh_sat" to 0.3f,
            "hi_hue" to 0.07f, "hi_sat" to 0.28f, "vignette" to 0.28f, "saturation" to -0.05f,
        ),
        "Golden Hour" to mapOf("temperature" to 0.45f, "tint" to 0.05f, "highlights" to -0.12f, "vibrance" to 0.25f, "hi_hue" to 0.1f, "hi_sat" to 0.25f, "shadows" to 0.1f),
        "Warm Film" to mapOf("contrast" to 0.12f, "temperature" to 0.2f, "vibrance" to 0.15f, "hi_hue" to 0.1f, "hi_sat" to 0.15f, "sh_hue" to 0.58f, "sh_sat" to 0.1f),
        "Green Film" to mapOf("tint" to -0.18f, "sh_hue" to 0.42f, "sh_sat" to 0.22f, "saturation" to -0.06f, "contrast" to 0.08f, "fade" to 0.1f),
        "Vintage 70s" to mapOf("fade" to 0.28f, "temperature" to 0.32f, "tint" to 0.1f, "saturation" to -0.12f, "hi_hue" to 0.13f, "hi_sat" to 0.2f, "sh_hue" to 0.93f, "sh_sat" to 0.15f, "contrast" to -0.1f),
        "Moody Blue" to mapOf("temperature" to -0.32f, "exposure" to -0.15f, "contrast" to 0.22f, "saturation" to -0.28f, "sh_hue" to 0.6f, "sh_sat" to 0.3f, "vignette" to 0.3f),
        "Pastel Dream" to mapOf("fade" to 0.35f, "contrast" to -0.25f, "vibrance" to 0.2f, "exposure" to 0.12f, "clarity" to -0.25f, "hi_hue" to 0.92f, "hi_sat" to 0.12f, "saturation" to -0.08f),
        "Cyberpunk" to mapOf("tint" to 0.35f, "temperature" to -0.2f, "contrast" to 0.25f, "vibrance" to 0.35f, "sh_hue" to 0.7f, "sh_sat" to 0.45f, "hi_hue" to 0.86f, "hi_sat" to 0.3f),
        "Summer Bright" to mapOf("exposure" to 0.12f, "vibrance" to 0.35f, "temperature" to 0.12f, "shadows" to 0.2f, "clarity" to 0.1f, "dehaze" to 0.1f),
        "Matte" to mapOf("fade" to 0.42f, "contrast" to -0.05f, "saturation" to -0.1f),
        "Bleach Bypass" to mapOf("saturation" to -0.55f, "contrast" to 0.4f, "clarity" to 0.3f, "blacks" to 0.1f, "fade" to 0.05f),
        "Black & White" to mapOf("saturation" to -1f, "contrast" to 0.2f, "clarity" to 0.15f),
        "Noir" to mapOf("saturation" to -1f, "contrast" to 0.55f, "vignette" to 0.5f, "exposure" to -0.1f, "clarity" to 0.2f),
        // ── cinematic ──
        "Desert Epic" to mapOf("temperature" to 0.35f, "saturation" to -0.3f, "contrast" to 0.18f, "fade" to 0.12f, "hi_hue" to 0.09f, "hi_sat" to 0.3f, "sh_hue" to 0.07f, "sh_sat" to 0.15f, "dehaze" to 0.1f),
        "Neon Noir" to mapOf("contrast" to 0.3f, "temperature" to -0.25f, "tint" to 0.25f, "sh_hue" to 0.55f, "sh_sat" to 0.5f, "hi_hue" to 0.06f, "hi_sat" to 0.45f, "vibrance" to 0.3f, "vignette" to 0.35f, "exposure" to -0.1f),
        "Tungsten 800" to mapOf("temperature" to -0.35f, "tint" to 0.08f, "contrast" to 0.12f, "fade" to 0.12f, "sh_hue" to 0.58f, "sh_sat" to 0.3f, "hi_hue" to 0.02f, "hi_sat" to 0.2f, "vibrance" to 0.1f),
        "Portrait 400" to mapOf("contrast" to -0.08f, "fade" to 0.1f, "temperature" to 0.12f, "saturation" to -0.08f, "vibrance" to 0.12f, "hsl_red_l" to 0.08f, "hi_hue" to 0.1f, "hi_sat" to 0.1f),
        "Matrix Green" to mapOf("tint" to -0.45f, "contrast" to 0.3f, "saturation" to -0.2f, "sh_hue" to 0.35f, "sh_sat" to 0.4f, "exposure" to -0.08f, "vignette" to 0.3f),
        "Apocalypse Orange" to mapOf("temperature" to 0.55f, "contrast" to 0.35f, "saturation" to 0.1f, "sh_hue" to 0.52f, "sh_sat" to 0.35f, "hi_hue" to 0.07f, "hi_sat" to 0.4f, "dehaze" to 0.2f, "clarity" to 0.25f),
        "Cold Space" to mapOf("temperature" to -0.3f, "saturation" to -0.4f, "contrast" to 0.15f, "fade" to 0.1f, "sh_hue" to 0.58f, "sh_sat" to 0.2f, "clarity" to 0.1f),
        "Pastel Symmetry" to mapOf("temperature" to 0.18f, "tint" to 0.1f, "saturation" to -0.05f, "vibrance" to 0.25f, "fade" to 0.25f, "contrast" to -0.15f, "hi_hue" to 0.97f, "hi_sat" to 0.12f, "exposure" to 0.1f),
        "Joker Teal" to mapOf("tint" to -0.25f, "temperature" to 0.15f, "contrast" to 0.25f, "sh_hue" to 0.47f, "sh_sat" to 0.45f, "hi_hue" to 0.14f, "hi_sat" to 0.3f, "saturation" to -0.1f),
        "Parisian" to mapOf("temperature" to 0.25f, "tint" to -0.15f, "vibrance" to 0.35f, "contrast" to 0.12f, "hsl_red_s" to 0.3f, "hsl_green_s" to 0.25f, "hi_hue" to 0.12f, "hi_sat" to 0.25f),
        "Moonlight" to mapOf("temperature" to -0.4f, "tint" to 0.15f, "exposure" to -0.25f, "saturation" to -0.35f, "sh_hue" to 0.66f, "sh_sat" to 0.4f, "contrast" to 0.1f, "vignette" to 0.35f),
        "Sepia" to mapOf("saturation" to -1f, "sh_hue" to 0.08f, "sh_sat" to 0.55f, "hi_hue" to 0.1f, "hi_sat" to 0.45f, "fade" to 0.15f, "contrast" to -0.05f),
        "Polaroid" to mapOf("fade" to 0.35f, "temperature" to 0.2f, "tint" to 0.12f, "saturation" to -0.2f, "contrast" to -0.12f, "sh_hue" to 0.5f, "sh_sat" to 0.15f, "vignette" to 0.2f),
        "Cross Process" to mapOf("contrast" to 0.3f, "saturation" to 0.15f, "sh_hue" to 0.6f, "sh_sat" to 0.45f, "hi_hue" to 0.16f, "hi_sat" to 0.45f, "tint" to -0.1f),
        "Autumn" to mapOf("temperature" to 0.4f, "hsl_green_h" to -0.4f, "hsl_green_s" to 0.2f, "hsl_yellow_s" to 0.3f, "vibrance" to 0.2f, "contrast" to 0.1f),
        "Soft Glow Film" to mapOf("exposure" to 0.1f, "clarity" to -0.45f, "fade" to 0.18f, "temperature" to 0.15f, "highlights" to -0.15f, "saturation" to -0.05f),
        "Anime Sky" to mapOf("vibrance" to 0.45f, "saturation" to 0.15f, "hsl_blue_s" to 0.35f, "hsl_cyan_s" to 0.3f, "exposure" to 0.08f, "shadows" to 0.15f, "clarity" to 0.15f),
        "Silver Screen" to mapOf("saturation" to -1f, "contrast" to 0.25f, "fade" to 0.12f, "clarity" to 0.1f, "vignette" to 0.25f),
    )
    fun look(name: String?): Map<String, Float>? = LOOKS.firstOrNull { it.first == name }?.second
}
