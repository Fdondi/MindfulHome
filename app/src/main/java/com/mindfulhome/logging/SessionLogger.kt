package com.mindfulhome.logging

import android.content.Context
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.data.SessionLog
import com.mindfulhome.data.SessionLogDao
import com.mindfulhome.data.SessionLogEvent
import com.mindfulhome.data.SessionLogWithCount
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists one DB-backed log session with child event rows.
 * Events are stored with timestamps, then rendered as markdown for display/copy.
 */
object SessionLogger {
    private const val TAG = "SessionLogger"
    private const val START_DEBOUNCE_MS = 10_000L

    private val headerDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeScope = CoroutineScope(writeDispatcher + SupervisorJob())

    data class SessionRecord(
        val id: Long,
        val title: String,
        val markdown: String,
        val eventCount: Int,
    )

    private var sessionLogDao: SessionLogDao? = null
    private val stateLock = Any()
    private val pendingEvents = mutableListOf<PendingEvent>()
    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var currentSessionStartedAtMs: Long = 0L
    @Volatile private var currentSessionEventCount: Int = 0
    @Volatile private var sessionStarting: Boolean = false

    private data class PendingEvent(
        val timestampMs: Long,
        val entry: String,
    )

    fun init(@Suppress("UNUSED_PARAMETER") context: Context, database: AppDatabase) {
        sessionLogDao = database.sessionLogDao()
    }

    fun startSession(initialEntry: String = "Phone unlocked") {
        val now = Date()
        val dao = sessionLogDao ?: return
        val nowMs = now.time
        val shouldDebounce = synchronized(stateLock) {
            (currentSessionId > 0L || sessionStarting) &&
                nowMs - currentSessionStartedAtMs in 0 until START_DEBOUNCE_MS &&
                currentSessionEventCount <= 1
        }
        if (shouldDebounce) {
            Log.d(TAG, "Ignoring duplicate startSession() within debounce window")
            return
        }

        synchronized(stateLock) {
            sessionStarting = true
            currentSessionId = 0L
            currentSessionStartedAtMs = nowMs
            currentSessionEventCount = 0
            pendingEvents.clear()
        }

        val title = "Session ${headerDateFmt.format(now)}"
        writeScope.launch {
            val sessionId = dao.insertSession(
                SessionLog(
                    startedAtMs = nowMs,
                    title = title,
                )
            )
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = sessionId,
                    timestampMs = nowMs,
                    entry = initialEntry,
                )
            )
            val toFlush: List<PendingEvent> = synchronized(stateLock) {
                currentSessionId = sessionId
                currentSessionStartedAtMs = nowMs
                currentSessionEventCount = 1
                sessionStarting = false
                pendingEvents.toList().also { pendingEvents.clear() }
            }
            toFlush.forEach { pending ->
                dao.insertEvent(
                    SessionLogEvent(
                        sessionId = sessionId,
                        timestampMs = pending.timestampMs,
                        entry = pending.entry,
                    )
                )
                synchronized(stateLock) {
                    currentSessionEventCount += 1
                }
            }
        }
    }

    fun log(entry: String) {
        val dao = sessionLogDao ?: return
        val now = System.currentTimeMillis()
        val sessionId: Long
        synchronized(stateLock) {
            if (currentSessionId <= 0L) {
                if (sessionStarting) {
                    pendingEvents.add(PendingEvent(now, entry))
                }
                return
            }
            sessionId = currentSessionId
            currentSessionEventCount += 1
        }
        writeScope.launch {
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = sessionId,
                    timestampMs = now,
                    entry = entry,
                )
            )
        }
    }

    suspend fun getAllSessions(): List<SessionRecord> {
        val dao = sessionLogDao ?: return emptyList()
        val sessions = withContext(writeDispatcher) { dao.getSessionsWithCounts() }
        return sessions.map { session ->
            val markdown = renderSessionMarkdown(dao, session)
            SessionRecord(
                id = session.id,
                title = session.title,
                markdown = markdown,
                eventCount = session.eventCount,
            )
        }
    }

    private suspend fun renderSessionMarkdown(
        dao: SessionLogDao,
        session: SessionLogWithCount,
    ): String {
        val events = dao.getEventsForSession(session.id)
        val body = buildString {
            append("# ")
            append(session.title)
            append("\n\n")
            events.forEach { event ->
                val time = timeFmt.format(Date(event.timestampMs))
                append("- **")
                append(time)
                append("** ")
                append(event.entry)
                append('\n')
            }
        }
        return body
    }
}
