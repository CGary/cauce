package com.cauce.feature.calendariogoogle.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.cauce.feature.calendariogoogle.ReunionSincronizada
import com.cauce.feature.calendariogoogle.SYNC_WINDOW
import com.cauce.feature.calendariogoogle.formatUtc
import java.time.Instant

/** Reads the next 14 days of occurrences from the Android calendar provider (SPEC 2.1, 2.6). */
internal object CalendarReader {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Null when the permission is missing or the provider cannot be read: never an empty "all gone". */
    fun read(context: Context, now: Instant): List<ReunionSincronizada>? {
        if (!hasPermission(context)) return null
        return try {
            readUnchecked(context, now)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private class Row(
        val eventId: Long,
        val serieId: Long,
        val originalTime: Long,
        val begin: Long,
        val end: Long,
        val titulo: String,
        val allDay: Boolean,
        val status: Int,
        val cuenta: String,
        val calendario: String?,
        val respuesta: String,
    )

    private fun readUnchecked(context: Context, now: Instant): List<ReunionSincronizada>? {
        val resolver = context.contentResolver
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now.toEpochMilli())
        ContentUris.appendId(builder, now.plus(SYNC_WINDOW).toEpochMilli())
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.ORIGINAL_ID,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val rows = mutableListOf<Row>()
        val cursor = resolver.query(
            builder.build(), projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val eventId = c.getLong(0)
                val originalId = if (c.isNull(1)) null else c.getLong(1)
                val begin = c.getLong(3)
                val originalTime = if (c.isNull(2)) begin else c.getLong(2)
                rows += Row(
                    eventId = eventId,
                    serieId = originalId ?: eventId,
                    originalTime = originalTime,
                    begin = begin,
                    end = c.getLong(4),
                    titulo = c.getString(5).orEmpty(),
                    allDay = c.getInt(6) == 1,
                    status = if (c.isNull(7)) CalendarContract.Events.STATUS_CONFIRMED else c.getInt(7),
                    calendario = c.getString(8),
                    cuenta = c.getString(9).orEmpty(),
                    respuesta = respuesta(if (c.isNull(10)) 0 else c.getInt(10)),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.eventId }.distinct()
        val reminders = earliestReminders(context, ids)
        return rows.map { r ->
            ReunionSincronizada(
                reunionId = "${r.serieId}:${r.originalTime}",
                serieId = r.serieId.toString(),
                cuenta = r.cuenta.ifBlank { "desconocida" }.take(200),
                calendario = r.calendario?.takeIf { it.isNotBlank() }?.take(200),
                titulo = r.titulo.ifBlank { "(sin título)" }.take(300),
                inicio = formatUtc(Instant.ofEpochMilli(r.begin)),
                fin = formatUtc(Instant.ofEpochMilli(r.end)),
                todoElDia = r.allDay,
                recordatorioMin = reminders[r.eventId],
                estado = if (r.status == CalendarContract.Events.STATUS_CANCELED) "cancelada" else "confirmada",
                respuesta = r.respuesta,
            )
        }.distinctBy { it.reunionId }
    }

    private fun inClause(ids: List<Long>): String = ids.joinToString(",", "(", ")")

    /** Earliest ALERT / DEFAULT reminder per event; `-1` (provider default) and other methods are ignored. */
    private fun earliestReminders(context: Context, ids: List<Long>): Map<Long, Int> {
        val out = mutableMapOf<Long, Int>()
        ids.chunked(500).forEach { chunk ->
            val selection = "${CalendarContract.Reminders.EVENT_ID} IN ${inClause(chunk)} AND " +
                "${CalendarContract.Reminders.METHOD} IN " +
                "(${CalendarContract.Reminders.METHOD_ALERT},${CalendarContract.Reminders.METHOD_DEFAULT})"
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                selection, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val minutes = c.getInt(1)
                    if (minutes < 0) continue
                    val id = c.getLong(0)
                    out[id] = minOf(out[id] ?: Int.MAX_VALUE, minutes)
                }
            }
        }
        return out
    }

    /** My own attendee status, as the provider derives it from the self attendee (`SELF_ATTENDEE_STATUS`). */
    private fun respuesta(status: Int): String = when (status) {
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "aceptada"
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentativa"
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "rechazada"
        else -> "sin_respuesta"
    }
}
