package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPackageFormatTest {

    @Test
    fun detectsExplicitDebSystemProperty() {
        val prev = System.getProperty("bitchord.package.format")
        try {
            System.setProperty("bitchord.package.format", "deb")
            assertEquals("deb", DesktopPackageFormat.current)
            assertTrue(DesktopPackageFormat.isDeb)
        } finally {
            if (prev != null) {
                System.setProperty("bitchord.package.format", prev)
            } else {
                System.clearProperty("bitchord.package.format")
            }
        }
    }
}
