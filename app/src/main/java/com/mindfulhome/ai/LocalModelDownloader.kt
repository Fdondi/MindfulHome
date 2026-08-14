package com.mindfulhome.ai

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class LocalModelDownloadFailure(
    val statusCode: Int?,
    val reason: String,
) : IOException(reason)

object LocalModelDownloader {
    const val MODEL_FILE_NAME = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.litertlm"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/$MODEL_FILE_NAME"
    const val LICENSE_PAGE_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun availableBytes(dir: File): Long {
        if (!dir.exists()) dir.mkdirs()
        return StatFs(dir.absolutePath).availableBytes
    }

    suspend fun download(
        destDir: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val dest = File(destDir, MODEL_FILE_NAME)
        val temp = File(destDir, "$MODEL_FILE_NAME.part")
        temp.delete()
        val request = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "MindfulHome/Android")
            .build()
        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                copySuccessfulBody(response.code, response.body, temp, dest, onProgress)
            }
        } catch (e: LocalModelDownloadFailure) {
            temp.delete()
            Result.failure(e)
        } catch (e: Exception) {
            temp.delete()
            Result.failure(
                LocalModelDownloadFailure(statusCode = null, reason = e.message ?: "Download failed"),
            )
        }
    }

    private suspend fun copySuccessfulBody(
        statusCode: Int,
        body: ResponseBody,
        temp: File,
        dest: File,
        onProgress: (Int) -> Unit,
    ): Result<File> {
        if (statusCode !in 200..299) {
            throw LocalModelDownloadFailure(statusCode = statusCode, reason = "HTTP $statusCode")
        }
        val written = copyBodyToTemp(body, temp, onProgress)
        return finishDownload(temp, dest, written)
    }

    private suspend fun copyBodyToTemp(
        body: ResponseBody,
        temp: File,
        onProgress: (Int) -> Unit,
    ): Long {
        val total = body.contentLength()
        var written = 0L
        var firstChunk = true
        body.byteStream().use { input ->
            temp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (firstChunk) {
                        firstChunk = false
                        rejectHtmlPayload(buf, n)
                    }
                    output.write(buf, 0, n)
                    written += n
                    onProgress(AiSetupLogic.downloadProgressPercent(written, total))
                }
            }
        }
        return written
    }

    private fun rejectHtmlPayload(buf: ByteArray, n: Int) {
        if (!AiSetupLogic.looksLikeHtml(buf.copyOf(n))) return
        throw LocalModelDownloadFailure(
            statusCode = 403,
            reason = "License or login page received instead of the model file",
        )
    }

    private fun finishDownload(temp: File, dest: File, written: Long): Result<File> {
        if (written <= 0L) {
            temp.delete()
            throw LocalModelDownloadFailure(statusCode = null, reason = "Empty download")
        }
        dest.delete()
        if (temp.renameTo(dest)) return Result.success(dest)
        temp.delete()
        throw LocalModelDownloadFailure(statusCode = null, reason = "Could not save model file")
    }
}
