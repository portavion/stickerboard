package com.portavion.stickerboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyClaimsTest {
    @Test
    fun manifestRequestsNoPermissions() {
        assertFalse(manifestFile().readText().contains("<uses-permission"))
    }

    @Test
    fun manifestDisablesBackup() {
        assertTrue(manifestFile().readText().contains("""android:allowBackup="false""""))
    }

    @Test
    fun footprintPresentIsMlKitOnly() {
        val observed = Footprint.observe(manifestFile(), javaClass.classLoader!!)
        assertEquals(emptySet<String>(), observed.permissions)
        assertEquals(setOf(Sdk.MlKitSubjectSegmentation), observed.present)
    }
}
