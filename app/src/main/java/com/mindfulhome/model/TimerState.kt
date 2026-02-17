package com.mindfulhome.model

sealed class TimerState {
    data object Idle : TimerState()
    data class Counting(val remainingMs: Long, val totalMs: Long) : TimerState()
    data class Expired(val overrunMs: Long) : TimerState()
}
