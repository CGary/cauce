package com.cauce.app

import android.content.Context
import com.cauce.core.events.validation.EventValidator
import com.cauce.core.events.validation.SchemaEventValidator

/** Debug builds validate every appended event against the embedded JSON Schemas. */
internal fun buildEventValidator(context: Context): EventValidator =
    SchemaEventValidator(context.assets)
