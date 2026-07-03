package com.app.smartform.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// SmartForm — dark athletic / neon palette
// ---------------------------------------------------------------------------

// Surfaces (blue-charcoal, darkest -> lightest)
val Charcoal900 = Color(0xFF0B0F14) // app background / window
val Charcoal800 = Color(0xFF12171F) // base surface
val Charcoal700 = Color(0xFF1A212B) // elevated card
val Charcoal600 = Color(0xFF232C38) // higher elevation / chips
val OutlineDim = Color(0xFF2A333F)
val OutlineFaint = Color(0xFF1E2732)

// Ink
val InkHigh = Color(0xFFECF1F6)
val InkMuted = Color(0xFF93A0B0)

// Brand accents
val NeonLime = Color(0xFFC6FF3D)     // primary accent
val ElectricCyan = Color(0xFF35E0D8) // secondary accent
val SoftViolet = Color(0xFFB39DFF)   // tertiary accent

// Accent containers (dim, for banners/chips)
val LimeContainer = Color(0xFF2C3A12)
val OnLimeContainer = Color(0xFFDCFF8A)
val TealContainer = Color(0xFF0E2E2C)
val OnTealContainer = Color(0xFF7FF3EC)
val VioletContainer = Color(0xFF241E3A)
val OnVioletContainer = Color(0xFFD6C9FF)

// Status
val NeonRed = Color(0xFFFF5C7A)
val RedContainer = Color(0xFF3A1620)
val OnRedContainer = Color(0xFFFFB3C1)

/**
 * Reserved status palette for rep quality. These are *status* colors (good / warning /
 * bad) and must always ship with a text label alongside — never color alone.
 */
object QualityColors {
    val Excellent = Color(0xFF7CFF9B)
    val Good = Color(0xFF39E67A)
    val Shallow = Color(0xFFFFC24B)
    val TooFast = Color(0xFFFF5C7A)
    val Neutral = Color(0xFF3A4655)

    fun forVerdict(verdict: String): Color = when (verdict) {
        "EXCELLENT" -> Excellent
        "GOOD" -> Good
        "SHALLOW" -> Shallow
        "TOO FAST", "TOO FAST + SHALLOW" -> TooFast
        else -> Neutral
    }
}
