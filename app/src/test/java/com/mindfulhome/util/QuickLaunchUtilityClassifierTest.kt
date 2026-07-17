package com.mindfulhome.util

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class QuickLaunchUtilityClassifierTest(
    private val packageName: String,
    private val expectedReason: String?,
    @Suppress("unused") private val caseName: String,
) {
    private data class FakeSignals(
        val ime: Set<String> = emptySet(),
        val home: Set<String> = emptySet(),
        val settings: String? = "com.android.settings",
        val dialer: String? = "com.google.android.dialer",
        val launchable: Set<String> = emptySet(),
        val labels: Map<String, String> = emptyMap(),
        val categories: Map<String, Int> = emptyMap(),
    ) : QuickLaunchUtilityClassifier.PackageSignals {
        override fun isInputMethodPackage(packageName: String) = packageName in ime
        override fun isHomeLauncherPackage(packageName: String) = packageName in home
        override fun isSettingsPackage(packageName: String) = packageName == settings
        override fun isDefaultDialerPackage(packageName: String) = packageName == dialer
        override fun hasLaunchIntent(packageName: String) = packageName in launchable
        override fun appLabel(packageName: String) = labels[packageName] ?: packageName
        override fun applicationCategory(packageName: String) =
            categories[packageName] ?: ApplicationInfo.CATEGORY_UNDEFINED
    }

    private val signals = FakeSignals(
        ime = setOf("com.google.android.inputmethod.latin"),
        home = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
        ),
        settings = "com.android.settings",
        dialer = "com.google.android.dialer",
        launchable = setOf(
            "com.instagram.android",
            "com.twitter.android",
            "com.android.settings",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.dialer",
            "com.android.camera",
            "com.example.albumviewer",
            "com.example.sharetarget",
        ),
        labels = mapOf(
            "com.example.albumviewer" to "My Gallery",
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "X",
        ),
        categories = mapOf(
            "com.example.sharetarget" to ApplicationInfo.CATEGORY_IMAGE,
        ),
    )

    private val classifier = QuickLaunchUtilityClassifier(
        signals = signals,
        selfPackageName = "com.mindfulhome",
    )

    @Test
    fun utilityReason_matchesExpected() {
        assertEquals(caseName, expectedReason, classifier.utilityReason(packageName))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{2}")
        fun cases(): Collection<Array<Any?>> = listOf(
            arrayOf("com.mindfulhome", "self", "self"),
            arrayOf(
                "com.google.android.inputmethod.latin",
                "keyboard/IME",
                "ime",
            ),
            arrayOf(
                "com.sec.android.app.launcher",
                "home launcher",
                "samsung_one_ui_home",
            ),
            arrayOf(
                "com.google.android.apps.nexuslauncher",
                "home launcher",
                "pixel_launcher",
            ),
            arrayOf("com.android.settings", "settings", "settings"),
            arrayOf("com.google.android.dialer", "default dialer", "dialer"),
            arrayOf(
                "com.android.systemui",
                "no launch intent",
                "systemui_no_launch",
            ),
            arrayOf(
                "com.android.permissioncontroller",
                "no launch intent",
                "permission_controller_no_launch",
            ),
            arrayOf(
                "com.android.camera",
                "utility exact package",
                "camera_exact",
            ),
            arrayOf(
                "com.example.albumviewer",
                "utility label keyword=gallery",
                "gallery_label",
            ),
            arrayOf(
                "com.example.sharetarget",
                "media category=IMAGE",
                "image_category",
            ),
            arrayOf("com.instagram.android", null, "instagram_monitored"),
            arrayOf("com.twitter.android", null, "x_monitored"),
            arrayOf("", null, "blank_monitor"),
        )
    }
}

class QuickLaunchUtilityClassifierNonParameterizedTest {

    @Test
    fun isUtility_trueWhenReasonPresent() {
        val classifier = QuickLaunchUtilityClassifier(
            signals = object : QuickLaunchUtilityClassifier.PackageSignals {
                override fun isInputMethodPackage(packageName: String) = false
                override fun isHomeLauncherPackage(packageName: String) = true
                override fun isSettingsPackage(packageName: String) = false
                override fun isDefaultDialerPackage(packageName: String) = false
                override fun hasLaunchIntent(packageName: String) = true
                override fun appLabel(packageName: String) = packageName
                override fun applicationCategory(packageName: String) =
                    ApplicationInfo.CATEGORY_UNDEFINED
            },
            selfPackageName = "com.mindfulhome",
        )
        assertEquals(true, classifier.isUtility("com.oem.launcher"))
        assertNull(
            QuickLaunchUtilityClassifier(
                signals = object : QuickLaunchUtilityClassifier.PackageSignals {
                    override fun isInputMethodPackage(packageName: String) = false
                    override fun isHomeLauncherPackage(packageName: String) = false
                    override fun isSettingsPackage(packageName: String) = false
                    override fun isDefaultDialerPackage(packageName: String) = false
                    override fun hasLaunchIntent(packageName: String) = true
                    override fun appLabel(packageName: String) = "Instagram"
                    override fun applicationCategory(packageName: String) =
                        ApplicationInfo.CATEGORY_UNDEFINED
                },
                selfPackageName = "com.mindfulhome",
            ).utilityReason("com.instagram.android"),
        )
    }
}
