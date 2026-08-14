package com.mindfulhome.ai

import com.mindfulhome.settings.SettingsManager

/**
 * Pure helpers for AI provider setup (Google / local / none).
 */
object AiSetupLogic {
    const val LOCAL_MODEL_SIZE_BYTES = 557L * 1024L * 1024L
    const val LOCAL_MODEL_SPACE_BUFFER_BYTES = 64L * 1024L * 1024L

    fun requiredBytesForLocalModel(): Long =
        LOCAL_MODEL_SIZE_BYTES + LOCAL_MODEL_SPACE_BUFFER_BYTES

    fun hasEnoughSpaceForLocalModel(availableBytes: Long): Boolean =
        availableBytes >= requiredBytesForLocalModel()

    fun isLocalOptionEnabled(hasModel: Boolean, availableBytes: Long): Boolean =
        hasModel || hasEnoughSpaceForLocalModel(availableBytes)

    fun downloadProgressPercent(bytesWritten: Long, contentLength: Long): Int {
        val total = if (contentLength > 0L) contentLength else LOCAL_MODEL_SIZE_BYTES
        if (total <= 0L) return 0
        return ((bytesWritten * 100L) / total).toInt().coerceIn(0, 100)
    }

    fun formatMegabytes(bytes: Long): String {
        val mb = (bytes + (1024L * 1024L) - 1L) / (1024L * 1024L)
        return "$mb MB"
    }

    fun isKnownAiMode(mode: String): Boolean = when (mode) {
        SettingsManager.AI_MODE_BACKEND,
        SettingsManager.AI_MODE_ON_DEVICE,
        SettingsManager.AI_MODE_NONE,
        -> true
        else -> false
    }

    fun normalizeAiMode(stored: String?): String =
        if (stored != null && isKnownAiMode(stored)) stored else SettingsManager.AI_MODE_BACKEND

    fun shouldUseBackend(mode: String): Boolean =
        mode == SettingsManager.AI_MODE_BACKEND

    fun shouldUseOnDevice(mode: String): Boolean =
        mode == SettingsManager.AI_MODE_ON_DEVICE

    fun isLicenseBlockedStatus(statusCode: Int): Boolean =
        statusCode == 401 || statusCode == 403

    fun looksLikeHtml(prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return false
        val text = String(prefix, Charsets.ISO_8859_1).trimStart()
        return text.startsWith("<") || text.startsWith("<!")
    }
}
