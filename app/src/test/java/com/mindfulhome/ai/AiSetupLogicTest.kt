package com.mindfulhome.ai

import com.mindfulhome.settings.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSetupLogicTest {

    @Test
    fun localSpaceGate_requiresModelSizePlusBuffer() {
        val need = AiSetupLogic.requiredBytesForLocalModel()
        assertFalse(AiSetupLogic.hasEnoughSpaceForLocalModel(need - 1))
        assertTrue(AiSetupLogic.hasEnoughSpaceForLocalModel(need))
        assertTrue(AiSetupLogic.hasEnoughSpaceForLocalModel(need + 1))
    }

    @Test
    fun localOptionEnabled_ifModelAlreadyPresent() {
        assertTrue(AiSetupLogic.isLocalOptionEnabled(hasModel = true, availableBytes = 0L))
        assertFalse(AiSetupLogic.isLocalOptionEnabled(hasModel = false, availableBytes = 0L))
        assertTrue(
            AiSetupLogic.isLocalOptionEnabled(
                hasModel = false,
                availableBytes = AiSetupLogic.requiredBytesForLocalModel(),
            ),
        )
    }

    @Test
    fun downloadProgressPercent_usesContentLengthOrModelSize() {
        assertEquals(0, AiSetupLogic.downloadProgressPercent(0L, 100L))
        assertEquals(50, AiSetupLogic.downloadProgressPercent(50L, 100L))
        assertEquals(100, AiSetupLogic.downloadProgressPercent(100L, 100L))
        assertEquals(100, AiSetupLogic.downloadProgressPercent(200L, 100L))
        val halfOfDefault = AiSetupLogic.LOCAL_MODEL_SIZE_BYTES / 2L
        assertEquals(50, AiSetupLogic.downloadProgressPercent(halfOfDefault, 0L))
    }

    @Test
    fun formatMegabytes_roundsUp() {
        assertEquals("1 MB", AiSetupLogic.formatMegabytes(1L))
        assertEquals("557 MB", AiSetupLogic.formatMegabytes(AiSetupLogic.LOCAL_MODEL_SIZE_BYTES))
    }

    @Test
    fun normalizeAiMode_defaultsToGoogleBackend() {
        assertEquals(SettingsManager.AI_MODE_BACKEND, AiSetupLogic.normalizeAiMode(null))
        assertEquals(SettingsManager.AI_MODE_BACKEND, AiSetupLogic.normalizeAiMode("bogus"))
        assertEquals(
            SettingsManager.AI_MODE_ON_DEVICE,
            AiSetupLogic.normalizeAiMode(SettingsManager.AI_MODE_ON_DEVICE),
        )
        assertEquals(
            SettingsManager.AI_MODE_NONE,
            AiSetupLogic.normalizeAiMode(SettingsManager.AI_MODE_NONE),
        )
        assertTrue(AiSetupLogic.shouldUseBackend(SettingsManager.AI_MODE_BACKEND))
        assertTrue(AiSetupLogic.shouldUseOnDevice(SettingsManager.AI_MODE_ON_DEVICE))
        assertFalse(AiSetupLogic.shouldUseBackend(SettingsManager.AI_MODE_NONE))
        assertFalse(AiSetupLogic.shouldUseOnDevice(SettingsManager.AI_MODE_NONE))
    }

    @Test
    fun licenseBlockedAndHtmlSniff() {
        assertTrue(AiSetupLogic.isLicenseBlockedStatus(401))
        assertTrue(AiSetupLogic.isLicenseBlockedStatus(403))
        assertFalse(AiSetupLogic.isLicenseBlockedStatus(500))
        assertTrue(AiSetupLogic.looksLikeHtml("<!DOCTYPE html>".toByteArray()))
        assertTrue(AiSetupLogic.looksLikeHtml("  <html".toByteArray()))
        assertFalse(AiSetupLogic.looksLikeHtml(byteArrayOf(0x00, 0x01, 0x02)))
        assertFalse(AiSetupLogic.looksLikeHtml(byteArrayOf()))
    }
}
