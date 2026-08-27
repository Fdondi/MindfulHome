package com.mindfulhome.ai.backend

import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.InputStream
import java.io.OutputStream

class AuthDataSerializer(private val aead: Aead) : Serializer<AuthData> {
    override val defaultValue: AuthData = AuthData()

    override suspend fun readFrom(input: InputStream): AuthData {
        val encryptedData = input.readBytes()
        if (encryptedData.isEmpty()) return defaultValue
        
        return try {
            val decryptedData = aead.decrypt(encryptedData, null)
            Json.decodeFromString(decryptedData.decodeToString())
        } catch (e: Exception) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: AuthData, output: OutputStream) {
        val json = Json.encodeToString(t)
        val encryptedData = aead.encrypt(json.encodeToByteArray(), null)
        output.write(encryptedData)
    }
}
