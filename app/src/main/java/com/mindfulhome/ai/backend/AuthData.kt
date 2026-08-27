package com.mindfulhome.ai.backend

import kotlinx.serialization.Serializable

@Serializable
data class AuthData(
    val googleIdToken: String? = null,
    val googleIdTokenExpiresMs: Long = 0L,
    val sessionToken: String? = null,
    val sessionExpiresMs: Long = 0L,
    val signedInEmail: String? = null
)
