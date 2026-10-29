package com.cauce.feature.alarmas

import com.cauce.core.events.CauceJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.Instant

class EventPayloadsTest {
    private val ts: Instant = Instant.parse("2026-10-23T14:00:00Z")
    private val pp: Instant = Instant.parse("2026-10-24T08:00:00Z")

    private fun assertJson(expected: String, actual: JsonObject) {
        assertEquals(CauceJson.wire.parseToJsonElement(expected), actual)
    }

    @Test
    fun `envelope follows envelope v1 with u1 and the given device id`() {
        val event = alarmaDesactivada("pixel-1", ts, "a1", "usuario", id = "e1")
        assertEquals("e1", event.id)
        assertEquals("u1", event.userId)
        assertEquals("pixel-1", event.deviceId)
        assertEquals("alarma_desactivada", event.type)
        assertEquals(1, event.v)
        assertEquals("2026-10-23T14:00:00Z", event.ts)
        assertEquals(null, event.serverSeq)
    }

    @Test
    fun `alarma creada carries the full state, remedio included`() {
        val repeticion = RepeticionForm(freq = "HOURLY", interval = 8, count = 15).toJson()
        val event = alarmaCreada(
            deviceId = "pixel-1",
            ts = ts,
            alarmaId = "a1",
            titulo = "Amoxicilina Juan",
            tipo = "remedio",
            hora = "08:00",
            repeticion = repeticion,
            destinatario = "Juan",
            remedio = "Amoxicilina",
        )
        assertEquals(3, event.v)
        assertJson(
            """
            {
              "alarma_id": "a1",
              "titulo": "Amoxicilina Juan",
              "tipo": "remedio",
              "hora": "08:00",
              "repeticion": { "freq": "HOURLY", "interval": 8, "count": 15 },
              "destinatario": "Juan",
              "remedio": "Amoxicilina"
            }
            """.trimIndent(),
            event.payload,
        )
    }

    @Test
    fun `alarma modificada is the same full state, not a diff`() {
        val repeticion = RepeticionForm(
            freq = "MONTHLY",
            monthlyModeIsOrdinal = true,
            monthlyOrdinal = -1,
            monthlyWeekday = "FR",
        ).toJson()
        val event = alarmaModificada(
            deviceId = "pixel-1",
            ts = ts,
            alarmaId = "a1",
            titulo = "Pago alquiler",
            tipo = "otro",
            hora = "09:30",
            repeticion = repeticion,
        )
        assertEquals(3, event.v)
        assertJson(
            """
            {
              "alarma_id": "a1",
              "titulo": "Pago alquiler",
              "tipo": "otro",
              "hora": "09:30",
              "repeticion": { "freq": "MONTHLY", "byday": ["-1FR"] }
            }
            """.trimIndent(),
            event.payload,
        )
    }

    @Test
    fun `tipo remedio exige destinatario y remedio`() {
        assertFailsWith<IllegalArgumentException> {
            alarmaCreada("pixel-1", ts, "a1", "t", "remedio", "08:00", RepeticionForm().toJson())
        }
        assertFailsWith<IllegalArgumentException> {
            alarmaCreada(
                "pixel-1", ts, "a1", "t", "remedio", "08:00", RepeticionForm().toJson(),
                destinatario = "Juan",
            )
        }
    }

    @Test
    fun `hora invalida se rechaza`() {
        assertFailsWith<IllegalArgumentException> {
            alarmaCreada("pixel-1", ts, "a1", "t", "otro", "24:00", RepeticionForm().toJson())
        }
    }

    @Test
    fun `disparada y atendida usan el instante nominal programada_para`() {
        val disparada = alarmaDisparada("pixel-1", ts, "a1", pp)
        assertJson(
            """{ "alarma_id": "a1", "programada_para": "2026-10-24T08:00:00Z" }""",
            disparada.payload,
        )

        val pospuesta = alarmaAtendida("pixel-1", ts, "a1", pp, "pospuesta", pospuestaN = 2)
        assertJson(
            """
            {
              "alarma_id": "a1",
              "programada_para": "2026-10-24T08:00:00Z",
              "accion": "pospuesta",
              "pospuesta_n": 2
            }
            """.trimIndent(),
            pospuesta.payload,
        )

        val hecho = alarmaAtendida("pixel-1", ts, "a1", pp, "hecho")
        assertJson(
            """
            {
              "alarma_id": "a1",
              "programada_para": "2026-10-24T08:00:00Z",
              "accion": "hecho"
            }
            """.trimIndent(),
            hecho.payload,
        )
    }

    @Test
    fun `pospuesta_n solo va con accion pospuesta y entre 1 y 3`() {
        assertFailsWith<IllegalArgumentException> {
            alarmaAtendida("pixel-1", ts, "a1", pp, "pospuesta", pospuestaN = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            alarmaAtendida("pixel-1", ts, "a1", pp, "hecho", pospuestaN = 1)
        }
    }

    @Test
    fun `dosis dada payload matches the schema`() {
        val event = dosisDada("pixel-1", ts, "a1", "Juan", "Amoxicilina", pp)
        assertJson(
            """
            {
              "alarma_id": "a1",
              "destinatario": "Juan",
              "remedio": "Amoxicilina",
              "programada_para": "2026-10-24T08:00:00Z"
            }
            """.trimIndent(),
            event.payload,
        )
    }

    @Test
    fun `motivo de desactivada esta acotado al enum`() {
        assertFailsWith<IllegalArgumentException> {
            alarmaDesactivada("pixel-1", ts, "a1", "borrada")
        }
        assertTrue(alarmaDesactivada("pixel-1", ts, "a1", "fin_tratamiento").type == "alarma_desactivada")
    }
}
