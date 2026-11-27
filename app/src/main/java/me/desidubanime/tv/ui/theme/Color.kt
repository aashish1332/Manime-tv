package me.desidubanime.tv.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// NEON NOIR — the app's own face.
//
// OLED televisions render true black as "off", so the ink is a very deep
// blue-black rather than pure black: posters then have an edge to sit against
// and the accent glow has something to bounce off. The accent is a
// violet-to-magenta pair (not the generic streaming red every client uses),
// with a warm gold reserved for NEW ribbons so "new" never competes with
// "selected".
// ---------------------------------------------------------------------------

val Bg = Color(0xFF06060B)
val Surface = Color(0xFF0E0E16)
val SurfaceHi = Color(0xFF1B1B27)
val SurfaceLine = Color(0xFF2A2A3A)

/** Primary accent — hot magenta. Selection, focus rings, kickers. */
val Primary = Color(0xFFFF2D6F)

/** Secondary accent — electric violet. Gradients and the brand mark. */
val Accent = Color(0xFF7C5CFF)

/** Warm gold — NEW ribbons only, so freshness reads differently from focus. */
val Gold = Color(0xFFFFC857)

val OnBg = Color(0xFFF4F3F9)
val Muted = Color(0xFF8E91A6)
val FocusRing = Color(0xFFFFFFFF)

/** Brand gradient: violet -> magenta, left to right. */
val AccentGradient = Brush.horizontalGradient(listOf(Accent, Primary))

/** Poster scrim: transparent at the top, ink at the bottom for the badges. */
val PosterScrim = Brush.verticalGradient(
    0.45f to Color.Transparent,
    1f to Color(0xE606060B),
)

/** Hero backdrop scrim: ink on the left (text) fading to transparent right. */
val HeroScrim = Brush.horizontalGradient(
    0f to Color(0xF206060B),
    0.5f to Color(0xC706060B),
    1f to Color(0x3306060B),
)

/** Thin separator used under section headers. */
val HairLine = Brush.horizontalGradient(
    0f to Color(0xFF3A3A52),
    0.7f to Color(0x1A3A3A52),
    1f to Color.Transparent,
)
