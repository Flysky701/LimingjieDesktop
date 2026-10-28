package com.landosol.toolbox.desktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTrayFontTest {
    @Test fun actualTrayMenuFontCanDisplayEveryMenuLabel() {
        val font = trayMenuFont()
        assertEquals("Actual tray font: ${font.family}", -1, font.canDisplayUpTo(TRAY_MENU_GLYPHS))
        assertTrue(font.size2D > 0)
    }
}
