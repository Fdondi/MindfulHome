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

    data class SessionHandle(val token: Long)

    private var sessionLogDao: SessionLogDao? = null
    private val stateLock = Any()
    private val pendingEvents = mutableListOf<PendingEvent>()
    @Volatile private var activeSessionToken: Long = 0L
    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var currentSessionStartedAtMs: Long = 0L
    @Volatile private var currentSessionEventCount: Int = 0
    @Volatile private var sessionStarting: Boolean = false

    private data class PendingEvent(
        val token: Long,
        val timestampMs: Long,
        val entry: String,
    )

    fun init(@Suppress("UNUSED_PARAMETER") context: Context, database: AppDatabase) {
        sessionLogDao = database.sessionLogDao()
    }

    fun startSession(initialEntry: String = "Phone unlocked"): SessionHandle {
        val now = Date()
        val dao = sessionLogDao ?: return SessionHandle(0L)
        val nowMs = now.time
        val token: Long
        val shouldDebounce = synchronized(stateLock) {
            (currentSessionId > 0L || sessionStarting) &&
                nowMs - currentSessionStartedAtMs in 0 until START_DEBOUNCE_MS &&
                currentSessionEventCount <= 1
        }
        if (shouldDebounce) {
            Log.d(TAG, "Ignoring duplicate startSession() within debounce window")
            return getActiveSessionHandle() ?: SessionHandle(0L)
        }

        synchronized(stateLock) {
            activeSessionToken += 1L
            token = activeSessionToken
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
                if (token != activeSessionToken) {
                    sessionStarting = false
                    emptyList()
                } else {
                    currentSessionId = sessionId
                    currentSessionStartedAtMs = nowMs
                    currentSessionEventCount = 1
                    sessionStarting = false
                    pendingEvents
                        .filter { it.token == token }
                        .also { pendingEvents.removeAll(it) }
                }
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
        return SessionHandle(token)
    }

    fun log(entry: String) {
        log(getActiveSessionHandle(), entry)
    }

    fun log(handle: SessionHandle?, entry: String) {
        if (handle == null || handle.token <= 0L) return
        val dao = sessionLogDao ?: return
        val now = System.currentTimeMillis()
        var sessionId: Long? = null
        var shouldRecover = false
        synchronized(stateLock) {
            if (handle.token != activeSessionToken) {
                Log.d(TAG, "Dropped stale log event for token=${handle.token}")
                return
            }
            if (currentSessionId <= 0L) {
                if (sessionStarting) {
                    pendingEvents.add(PendingEvent(handle.token, now, entry))
                } else {
                    pendingEvents.add(PendingEvent(handle.token, now, entry))
                    sessionStarting = true
                    shouldRecover = true
                }
            } else {
                sessionId = currentSessionId
                currentSessionEventCount += 1
            }
        }
        if (sessionId != null) {
            val targetSessionId = sessionId
            writeScope.launch {
                dao.insertEvent(
                    SessionLogEvent(
                        sessionId = targetSessionId,
                        timestampMs = now,
                        entry = entry,
                    )
                )
            }
        }
        if (shouldRecover) {
            writeScope.launch {
                recoverOrCreateSessionAndFlush(dao, handle.token)
            }
        }
    }

    fun getActiveSessionHandle(): SessionHandle? {
        val token = activeSessionToken
        if (token <= 0L) return null
        return SessionHandle(token)
    }

    fun handleFromToken(token: Long): SessionHandle? {
        if (token <= 0L) return null
        return SessionHandle(token)
    }

    private suspend fun recoverOrCreateSessionAndFlush(dao: SessionLogDao, token: Long) {
        synchronized(stateLock) {
            if (token != activeSessionToken) {
                sessionStarting = false
                pendingEvents.removeAll { it.token == token }
                return
            }
        }
        val recovered = dao.getLatestSessionWithCount()
        val sessionId: Long
        val startedAtMs: Long
        var eventCount = 0

        if (recovered != null) {
            sessionId = recovered.id
            startedAtMs = recovered.startedAtMs
            eventCount = recovered.eventCount
            Log.d(TAG, "Recovered existing session id=$sessionId")
        } else {
            val now = Date()
            val nowMs = now.time
            val title = "Session ${headerDateFmt.format(now)}"
            sessionId = dao.insertSession(
                SessionLog(
                    startedAtMs = nowMs,
                    title = title,
                )
            )
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = sessionId,
                    timestampMs = nowMs,
                    entry = "Session resumed",
                )
            )
            startedAtMs = nowMs
            eventCount = 1
            Log.w(TAG, "No session found; created recovery session id=$sessionId")
        }

        val toFlush: List<PendingEvent>
        val activeSessionId: Long
        synchronized(stateLock) {
            if (token != activeSessionToken) {
                sessionStarting = false
                pendingEvents.removeAll { it.token == token }
                return
            }
            if (currentSessionId <= 0L) {
                currentSessionId = sessionId
                currentSessionStartedAtMs = startedAtMs
                currentSessionEventCount = eventCount
            }
            sessionStarting = false
            activeSessionId = currentSessionId
            toFlush = pendingEvents
                .filter { it.token == token }
                .also { pendingEvents.removeAll(it) }
        }

        if (toFlush.isEmpty()) return
        toFlush.forEach { pending ->
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = activeSessionId,
                    timestampMs = pending.timestampMs,
                    entry = pending.entry,
                )
            )
        }
        synchronized(stateLock) {
            currentSessionEventCount += toFlush.size
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
