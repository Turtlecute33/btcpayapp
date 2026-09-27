package com.btcpayapp.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private enum class OverlayMode { Fast, Slow, Unknown }

private object OverlayModeSerializer : FallbackEnumSerializer<OverlayMode>("OverlayMode", OverlayMode.entries.toTypedArray(), OverlayMode.Unknown)

/** A stand-in for a whole-object PUT model such as the store settings. */
@Serializable
private data class OverlaySettings(
    val name: String,
    @Serializable(with = OverlayModeSerializer::class) val mode: OverlayMode = OverlayMode.Fast,
    val note: String? = null,
    val label: String? = null,
)

/**
 * Whole-object PUTs start from the server's raw JSON and lay only this app's
 * edits on top, so a field a newer server added, or an enum value this app
 * cannot read, survives a save.
 */
class JsonOverlayTest {

    private val json = ApiJson.instance

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    @Test
    fun `overlaid keeps unknown keys, lets edits win and removes dropped keys`() {
        val raw = obj("""{"name":"a","mode":"Fast","addedLater":{"x":1},"secret":"s"}""")
        val result = raw.overlaid(obj("""{"name":"b","secret":"t"}"""), drop = setOf("secret"))
        assertEquals(obj("""{"name":"b","mode":"Fast","addedLater":{"x":1}}"""), result)
    }

    @Test
    fun `editsOf omits nulls and clears only the clearable keys`() {
        val edits = ApiJson.editsOf(OverlaySettings.serializer(), OverlaySettings(name = "b"), clearable = setOf("note"))
        assertEquals(JsonPrimitive("b"), edits["name"])
        assertEquals(JsonNull, edits["note"])
        assertFalse("a null the user cannot clear must mean keep", "label" in edits)
    }

    @Test
    fun `a clearable key with a value is sent as that value`() {
        val edits = ApiJson.editsOf(OverlaySettings.serializer(), OverlaySettings(name = "b", note = "n"), clearable = setOf("note"))
        assertEquals(JsonPrimitive("n"), edits["note"])
    }

    @Test
    fun `an enum value this app cannot read survives the overlay`() {
        val raw = obj("""{"name":"a","mode":"Turbo"}""")
        val loaded = json.decodeFromJsonElement(OverlaySettings.serializer(), raw)
        assertEquals(OverlayMode.Unknown, loaded.mode)

        val body = raw.overlaid(ApiJson.editsOf(OverlaySettings.serializer(), loaded.copy(name = "b")))

        assertEquals(JsonPrimitive("Turbo"), body["mode"])
        assertEquals(JsonPrimitive("b"), body["name"])
    }

    @Test
    fun `an unknown enum is written as null, never as a made-up name`() {
        assertEquals("null", json.encodeToString(OverlayModeSerializer, OverlayMode.Unknown))
        assertEquals("\"Slow\"", json.encodeToString(OverlayModeSerializer, OverlayMode.Slow))
        assertTrue(json.encodeToString(OverlaySettings.serializer(), OverlaySettings("a", OverlayMode.Unknown)).contains("\"mode\":null"))
    }

    @Test
    fun `decimal text reads numbers and strings`() {
        assertEquals("0.0", json.decodeFromString(DecimalTextSerializer, "0.0"))
        assertEquals("0.5", json.decodeFromString(DecimalTextSerializer, "\"0.5\""))
    }

    @Test
    fun `decimal text writes a plain decimal string`() {
        assertEquals("\"0.5\"", json.encodeToString(DecimalTextSerializer, "0.5"))
        assertEquals("\"100\"", json.encodeToString(DecimalTextSerializer, "1E+2"))
    }

    @Test
    fun `decimal text refuses anything the server could misread`() {
        // Newtonsoft may read "0,5" as 5 under some cultures.
        for (value in listOf("0,5", "", "abc", "1e999999999")) {
            assertThrows(value, SerializationException::class.java) { json.encodeToString(DecimalTextSerializer, value) }
        }
    }
}
