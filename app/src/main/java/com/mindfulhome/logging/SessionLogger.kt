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
        val startedAtMs: Long,
        val title: String,
        val markdown: String,
        val eventCount: Int,
    )

    data class SessionSummary(
        val id: Long,
        val startedAtMs: Long,
        val title: String,
        val eventCount: Int,
        val firstEventPreview: String,
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
        if (shouldDebounceNow(nowMs)) {
            Log.d(TAG, "Ignoring duplicate startSession() within debounce window")
            return getActiveSessionHandle() ?: SessionHandle(0L)
        }
        val token = beginNewSessionToken(nowMs)
        val title = "Session ${headerDateFmt.format(now)}"
        writeScope.launch { persistNewSession(dao, token, nowMs, title, initialEntry) }
        return SessionHandle(token)
    }

    private fun shouldDebounceNow(nowMs: Long): Boolean = synchronized(stateLock) {
        shouldDebounceSessionStart(
            hasActiveOrStartingSession = currentSessionId > 0L || sessionStarting,
            ageMs = nowMs - currentSessionStartedAtMs,
            debounceMs = START_DEBOUNCE_MS,
            eventCount = currentSessionEventCount,
        )
    }

    private fun beginNewSessionToken(nowMs: Long): Long = synchronized(stateLock) {
        activeSessionToken += 1L
        val token = activeSessionToken
        sessionStarting = true
        currentSessionId = 0L
        currentSessionStartedAtMs = nowMs
        currentSessionEventCount = 0
        pendingEvents.clear()
        token
    }

    private suspend fun persistNewSession(
        dao: SessionLogDao,
        token: Long,
        nowMs: Long,
        title: String,
        initialEntry: String,
    ) {
        val sessionId = dao.insertSession(SessionLog(startedAtMs = nowMs, title = title))
        dao.insertEvent(SessionLogEvent(sessionId = sessionId, timestampMs = nowMs, entry = initialEntry))
        val toFlush: List<PendingEvent> = synchronized(stateLock) {
            if (token != activeSessionToken) {
                sessionStarting = false
                emptyList()
            } else {
                currentSessionId = sessionId
                currentSessionStartedAtMs = nowMs
                currentSessionEventCount = 1
                sessionStarting = false
                pendingEvents.filter { it.token == token }.also { pendingEvents.removeAll(it) }
            }
        }
        toFlush.forEach { pending ->
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = sessionId,
                    timestampMs = pending.timestampMs,
                    entry = pending.entry,
                ),
            )
            synchronized(stateLock) { currentSessionEventCount += 1 }
        }
    }

    fun log(entry: String) {
        log(getActiveSessionHandle(), entry)
    }

    fun log(
        handle: SessionHandle?,
        entry: String,
    ) {
        if (handle == null || handle.token <= 0L) return
        val dao = sessionLogDao ?: return
        val now = System.currentTimeMillis()
        val decision = synchronized(stateLock) {
            decideSessionLogWrite(
                handleToken = handle.token,
                activeToken = activeSessionToken,
                currentSessionId = currentSessionId,
                sessionStarting = sessionStarting,
            )
        }
        applySessionLogDecision(dao, handle.token, now, entry, decision)
    }

    private fun applySessionLogDecision(
        dao: SessionLogDao,
        token: Long,
        now: Long,
        entry: String,
        decision: SessionLogWriteDecision,
    ) {
        when (decision) {
            SessionLogWriteDecision.Stale ->
                Log.d(TAG, "Dropped stale log event for token=$token")
            SessionLogWriteDecision.QueuePending ->
                queuePendingEvent(token, now, entry)
            SessionLogWriteDecision.QueueAndRecover -> {
                queuePendingEvent(token, now, entry, markStarting = true)
                writeScope.launch { recoverOrCreateSessionAndFlush(dao, token) }
            }
            is SessionLogWriteDecision.WriteNow ->
                writeLogEventNow(dao, decision.sessionId, now, entry)
        }
    }

    private fun queuePendingEvent(
        token: Long,
        now: Long,
        entry: String,
        markStarting: Boolean = false,
    ) {
        synchronized(stateLock) {
            pendingEvents.add(PendingEvent(token, now, entry))
            if (markStarting) sessionStarting = true
        }
    }

    private fun writeLogEventNow(
        dao: SessionLogDao,
        sessionId: Long,
        now: Long,
        entry: String,
    ) {
        synchronized(stateLock) {
            currentSessionEventCount += 1
        }
        writeScope.launch {
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = sessionId,
                    timestampMs = now,
                    entry = entry,
                ),
            )
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
        val (sessionId, startedAtMs, eventCount) = if (recovered != null) {
            Log.d(TAG, "Recovered existing session id=${recovered.id}")
            Triple(recovered.id, recovered.startedAtMs, recovered.eventCount)
        } else {
            createRecoverySession(dao)
        }
        flushPendingAfterRecover(dao, token, sessionId, startedAtMs, eventCount)
    }

    private suspend fun createRecoverySession(dao: SessionLogDao): Triple<Long, Long, Int> {
        val now = Date()
        val nowMs = now.time
        val title = "Session ${headerDateFmt.format(now)}"
        val sessionId = dao.insertSession(SessionLog(startedAtMs = nowMs, title = title))
        dao.insertEvent(
            SessionLogEvent(sessionId = sessionId, timestampMs = nowMs, entry = "Session resumed"),
        )
        Log.w(TAG, "No session found; created recovery session id=$sessionId")
        return Triple(sessionId, nowMs, 1)
    }

    private suspend fun flushPendingAfterRecover(
        dao: SessionLogDao,
        token: Long,
        sessionId: Long,
        startedAtMs: Long,
        eventCount: Int,
    ) {
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
            toFlush = pendingEvents.filter { it.token == token }.also { pendingEvents.removeAll(it) }
        }
        if (toFlush.isEmpty()) return
        toFlush.forEach { pending ->
            dao.insertEvent(
                SessionLogEvent(
                    sessionId = activeSessionId,
                    timestampMs = pending.timestampMs,
                    entry = pending.entry,
                ),
            )
        }
        synchronized(stateLock) { currentSessionEventCount += toFlush.size }
    }

    suspend fun getAllSessions(): List<SessionRecord> {
        val dao = sessionLogDao ?: return emptyList()
        val sessions = withContext(writeDispatcher) { dao.getSessionsWithCounts() }
        return sessions.map { session -> toSessionRecord(dao, session) }
    }

    suspend fun getSessionsInTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<SessionRecord> {
        val dao = sessionLogDao ?: return emptyList()
        val sessions = withContext(writeDispatcher) {
            dao.getSessionsWithCountsInRange(startMs, endMs)
        }
        return sessions
            .sortedByDescending { it.startedAtMs }
            .map { session -> toSessionRecord(dao, session) }
    }

    suspend fun getSessionSummariesInTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<SessionSummary> {
        val dao = sessionLogDao ?: return emptyList()
        val sessions = withContext(writeDispatcher) {
            dao.getSessionsWithCountsInRange(startMs, endMs)
        }
        if (sessions.isEmpty()) return emptyList()
        val eventsBySession = withContext(writeDispatcher) {
            dao.getEventsForSessions(sessions.map { it.id })
                .groupBy { it.sessionId }
        }
        return sessions
            .sortedByDescending { it.startedAtMs }
            .map { session ->
                SessionSummary(
                    id = session.id,
                    startedAtMs = session.startedAtMs,
                    title = session.title,
                    eventCount = session.eventCount,
                    firstEventPreview = eventsBySession[session.id]
                        ?.firstOrNull()
                        ?.entry
                        .orEmpty(),
                )
            }
    }

    suspend fun getSessionMarkdown(sessionId: Long, title: String): String {
        val dao = sessionLogDao ?: return ""
        val events = withContext(writeDispatcher) { dao.getEventsForSession(sessionId) }
        return renderSessionMarkdown(title, events)
    }

    private suspend fun toSessionRecord(
        dao: SessionLogDao,
        session: SessionLogWithCount,
    ): SessionRecord {
        val events = withContext(writeDispatcher) { dao.getEventsForSession(session.id) }
        val markdown = renderSessionMarkdown(session.title, events)
        return SessionRecord(
            id = session.id,
            startedAtMs = session.startedAtMs,
            title = session.title,
            markdown = markdown,
            eventCount = session.eventCount,
        )
    }

    private fun renderSessionMarkdown(
        title: String,
        events: List<SessionLogEvent>,
    ): String {
        val body = buildString {
            append("# ")
            append(title)
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
