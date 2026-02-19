package com.mindfulhome.logging

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one markdown file per phone-unlock session.
 * Every notable event (app open, timer set/expired, AI chat, karma change, …)
 * is appended as a timestamped bullet.
 *
 * All file I/O is dispatched to a single background thread so callers on the
 * main thread are never blocked.
 */
object SessionLogger {

    private val fileDateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val headerDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeScope = CoroutineScope(writeDispatcher + SupervisorJob())

    private var logsDir: File? = null
    @Volatile private var currentFile: File? = null

    fun init(context: Context) {
        logsDir = File(context.filesDir, "logs").also { it.mkdirs() }
    }

    fun startSession() {
        val now = Date()
        val file = File(logsDir ?: return, "${fileDateFmt.format(now)}.md")
        currentFile = file
        val header = "# Session ${headerDateFmt.format(now)}\n\n"
        val line = "- **${timeFmt.format(now)}** Phone unlocked\n"
        writeScope.launch {
            file.writeText(header)
            file.appendText(line)
        }
    }

    fun log(entry: String) {
        val file = currentFile ?: return
        val line = "- **${timeFmt.format(Date())}** $entry\n"
        writeScope.launch {
            file.appendText(line)
        }
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
}
