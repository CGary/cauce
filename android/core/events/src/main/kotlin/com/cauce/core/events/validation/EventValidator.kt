package com.cauce.core.events.validation

import com.cauce.core.events.Envelope

/**
 * Validates an event before it is appended. Implementations are free to be expensive: the store
 * only calls them when the app is built from a source set that provides one.
 */
fun interface EventValidator {
    /** Returns human-readable problems; an empty list means the event is valid. */
    fun validate(event: Envelope): List<String>
}

object NoopEventValidator : EventValidator {
    override fun validate(event: Envelope): List<String> = emptyList()
}
