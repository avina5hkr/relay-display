package com.avinash.relaydisplay.ui.theme

import androidx.compose.ui.graphics.Color

// Deliberately high-contrast: the controller phone has a damaged screen with lines through it,
// so low-contrast greys and thin dividers are unreadable there.
val RelayBlue = Color(0xFF0B4F9E)
val RelayBlueLight = Color(0xFFAFCBFF)
val RelayTeal = Color(0xFF00696E)
val RelayTealLight = Color(0xFF6FF6FE)
val RelayAmber = Color(0xFF7A5900)
val RelayAmberLight = Color(0xFFF7BE2C)

val SurfaceLight = Color(0xFFFBFCFF)
val SurfaceDark = Color(0xFF0F1417)
val OnSurfaceLight = Color(0xFF11181C)
val OnSurfaceDark = Color(0xFFE1E3E5)

// Presentation is always a true black background: it is what an OLED-ish panel shows cleanest
// and what makes a white QR code scan fastest.
val PresentationBackground = Color(0xFF000000)
val PresentationForeground = Color(0xFFFFFFFF)
