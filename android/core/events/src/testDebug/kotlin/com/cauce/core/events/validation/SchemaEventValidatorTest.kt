package com.cauce.core.events.validation

import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import com.cauce.core.events.Fixtures
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the debug validator against the real contract on the JVM: the assets copy only changes
 * where the files live, not the schema paths.
 */
class SchemaEventValidatorTest {
    private val validator = SchemaEventValidator(FileSchemaSource(Fixtures.schemaDir()))

    @Test
    fun `every fixture event validates against envelope and payload schemas`() {
        val failures = Fixtures.loadAll().flatMap { file ->
            Fixtures.eventsOf(file.fixture).flatMap { event ->
                validator.validate(event).map { "${file.path.name}/${event.id}: $it" }
            }
        }

        assertTrue(failures.isEmpty(), "Events that do not validate:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `a payload without a schema is reported`() {
        val event = event(type = "not_an_event", payload = "{}")

        val errors = validator.validate(event)

        assertTrue(errors.any { "no JSON Schema at events/not_an_event.v1.json" in it }, errors.toString())
    }

    @Test
    fun `a payload that violates its schema is reported`() {
        val event = event(
            type = "dosis_dada",
            payload = """{"alarma_id":"a1"}""",
        )

        val errors = validator.validate(event)

        assertTrue(errors.any { it.startsWith("payload:") }, errors.toString())
    }

    private fun event(type: String, payload: String): Envelope = Envelope(
        id = "e1",
        userId = "u1",
        deviceId = "test",
        type = type,
        v = 1,
        ts = "2026-10-22T12:00:00Z",
        payload = CauceJson.wire.parseToJsonElement(payload).jsonObject,
    )
}

private class FileSchemaSource(private val root: File) : SchemaSource {
    override fun open(path: String): InputStream? {
        val file = root.resolve(path)
        return if (file.isFile) FileInputStream(file) else null
    }
}
