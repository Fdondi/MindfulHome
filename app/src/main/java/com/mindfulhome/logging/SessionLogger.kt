package com.mindfulhome.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one markdown file per phone-unlock session.
 * Every notable event (app open, timer set/expired, AI chat, karma change, …)
 * is appended as a timestamped bullet.
 */
object SessionLogger {

    private val fileDateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val headerDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var logsDir: File? = null
    private var currentFile: File? = null

    fun init(context: Context) {
        logsDir = File(context.filesDir, "logs").also { it.mkdirs() }
    }

    @Synchronized
    fun startSession() {
        val now = Date()
        val file = File(logsDir ?: return, "${fileDateFmt.format(now)}.md")
        currentFile = file
        file.writeText("# Session ${headerDateFmt.format(now)}\n\n")
        append("Phone unlocked")
    }

    @Synchronized
    fun log(entry: String) {
        append(entry)
    }

    /** All session files, newest first. */
    fun getAllSessionFiles(): List<File> {
        return logsDir?.listFiles { f -> f.extension == "md" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** Read a single session file's content. */
    fun readSession(file: File): String {
        return if (file.exists()) file.readText() else ""
    }

    private fun append(entry: String) {
        val file = currentFile ?: return
        val time = timeFmt.format(Date())
        file.appendText("- **$time** $entry\n")
    }
}
