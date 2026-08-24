package com.mindfulhome.ai

/**
 * Where AI conversations run. [storageTag] is the SharedPreferences value.
 */
enum class AiMode(val storageTag: String) {
    BACKEND("backend"),
    ON_DEVICE("on_device"),
    NONE("none"),
    ;

    val usesBackend: Boolean get() = this == BACKEND
    val usesOnDevice: Boolean get() = this == ON_DEVICE

    companion object {
        val DEFAULT = BACKEND

        fun fromStored(stored: String?): AiMode =
            entries.firstOrNull { it.storageTag == stored } ?: DEFAULT
    }
}
