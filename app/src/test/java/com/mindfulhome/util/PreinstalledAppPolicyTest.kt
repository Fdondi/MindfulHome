package com.mindfulhome.util

import com.mindfulhome.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreinstalledAppPolicyTest {

    @Test
    fun knownMediaPackages_detected() {
        assertTrue(PreinstalledAppPolicy.isKnownMediaPackage("com.instagram.android"))
        assertTrue(PreinstalledAppPolicy.isKnownMediaPackage("com.google.android.youtube"))
        assertTrue(PreinstalledAppPolicy.isKnownMediaPackage("com.zhiliaoapp.musically"))
        assertFalse(PreinstalledAppPolicy.isKnownMediaPackage("com.android.phone"))
        assertFalse(PreinstalledAppPolicy.isKnownMediaPackage("com.google.android.dialer"))
    }

    @Test
    fun unrestrictedSystemCandidates_excludesMediaAndNonSystem() {
        val apps = listOf(
            AppInfo("com.android.phone", "Phone", null, isSystemApp = true),
            AppInfo("com.instagram.android", "Instagram", null, isSystemApp = true),
            AppInfo("com.user.app", "User", null, isSystemApp = false),
        )
        val candidates = PreinstalledAppPolicy.unrestrictedSystemCandidates(apps)
        assertEquals(listOf("com.android.phone"), candidates.map { it.packageName })
    }
}
