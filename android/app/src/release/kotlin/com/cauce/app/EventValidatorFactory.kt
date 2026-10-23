package com.cauce.app

import android.content.Context
import com.cauce.core.events.validation.EventValidator
import com.cauce.core.events.validation.NoopEventValidator

/** Release builds skip schema validation: the contract is enforced in debug and on the server. */
internal fun buildEventValidator(context: Context): EventValidator = NoopEventValidator
