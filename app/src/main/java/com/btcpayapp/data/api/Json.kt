package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.LabelData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * JSON configuration and the handful of tolerant serializers BTCPay's wire
 * format needs.
 *
 * Three quirks drive everything in this file:
 *
 *  1. **Decimals are sent as JSON strings** (`"5.00"`, not `5.00`) by a custom
 *     converter on the server — except `paymentTolerance` and `feeRate`, which
 *     are plain numbers. Rather than track which is which, [BigDecimalSerializer]
 *     accepts either and always writes the string form the server expects.
 *     Amounts are never parsed into `Double`; binary floating point has no
 *     business anywhere near a balance.
 *
 *  2. **Some integers arrive as strings too** (`blockHeight`, `confirmations`).
 *     Same treatment via [TolerantLongSerializer].
 *
 *  3. **New enum members appear when the server is upgraded.** A client that
 *     throws on an unrecognised invoice status would break the whole screen for
 *     one unknown row, so every API enum degrades to an `Unknown` member
 *     instead (see [FallbackEnumSerializer]).
 */
object ApiJson {

    val instance: Json = Json {
        // The server documents `[JsonExtensionData]` on many models and adds
        // fields between releases; an old client must keep working.
        ignoreUnknownKeys = true
        // Omit nulls, but write everything else — including values that happen
        // to equal a Kotlin default.
        //
        // This split matters. BTCPay has two kinds of write endpoint:
        //   * partial updates, where an absent field means "leave unchanged"
        //     (`UpdatePaymentMethodRequest`, the email settings password). Those
        //     models are all-nullable, so `explicitNulls = false` gives the
        //     right behaviour.
        //   * whole-object replacements (`PUT /stores/{id}`, webhooks), where an
        //     absent field is reset to the server's own default.
        //
        // With `encodeDefaults = false` the second kind silently breaks: turning
        // a flag back to its default value omits it, so the change never
        // reaches the server and the UI and the store disagree.
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
        isLenient = false
        prettyPrint = false
    }

    /** Pretty printer used only for the developer-facing raw-response viewer. */
    val pretty: Json = Json(instance) { prettyPrint = true }

    /**
     * The fields of [value] a whole-object PUT should change, for [overlaid].
     *
     * Every null is removed first. `explicitNulls = false` already omits a null
     * property, and what is left is an `Unknown` enum (see
     * [FallbackEnumSerializer]): a value this app could not read, which the
     * overlay must leave as the server sent it. Then each [clearable] key that
     * is absent is sent as an explicit null. The list is explicit because an
     * absent field must mean "keep", and only a field the user can empty on
     * the screen may mean "clear".
     */
    fun <T> editsOf(serializer: SerializationStrategy<T>, value: T, clearable: Set<String> = emptySet()): JsonObject {
        val edits = instance.encodeToJsonElement(serializer, value).jsonObject.filterValues { it !is JsonNull }
        return JsonObject(edits + clearable.filter { it !in edits }.associateWith { JsonNull })
    }
}

/**
 * The server's own JSON with [edits] laid on top, minus the [drop] keys.
 *
 * Whole-object PUTs (apps, a payment method's config) replace every field,
 * and the server resets whatever the body leaves out. A typed model only
 * knows the fields of the release it was written for, so re-encoding it would
 * wipe any field a newer server added. Starting from the raw object the server
 * sent keeps those, and the edits win only where this app has something to
 * say. Shallow on purpose: a nested object is replaced whole, like the server
 * does.
 */
internal fun JsonObject.overlaid(edits: JsonObject, drop: Set<String> = emptySet()): JsonObject =
    JsonObject((this - drop) + (edits - drop))

/** Accepts `"1.23"` or `1.23`; always emits `"1.23"`. */
internal object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: return BigDecimal(decoder.decodeString())
        val primitive = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected a decimal amount")
        val value = primitive.content.takeIf { it.length <= 1024 }?.toBigDecimalOrNull()
            ?: throw SerializationException("Invalid decimal amount")
        if (value.scale() !in -1000..1000 || value.precision() > 1000) {
            throw SerializationException("Decimal amount is out of range")
        }
        return value
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }
}

/**
 * A decimal kept as the server's text: reads a JSON number or a string, and
 * writes a string holding a plain `.` decimal.
 *
 * For fields the app shows and edits as text (a rate spread, a Lightning
 * address limit) without doing arithmetic on them. The write is strict on
 * purpose: Newtonsoft reads a decimal string with ',' allowed as a thousands
 * separator, so `"0,5"` may become 5, a tenfold change nobody asked for.
 * Anything that is not a plain decimal therefore fails the encode instead of
 * reaching the server. Callers normalise user input with
 * `Amounts.parse(...).toPlainString()`.
 */
internal object DecimalTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DecimalText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()

    override fun serialize(encoder: Encoder, value: String) {
        val decimal = try {
            BigDecimal(value)
        } catch (e: NumberFormatException) {
            throw SerializationException("not a plain decimal")
        }
        // The same bound as [BigDecimalSerializer]: `toPlainString()` of 1e999999999
        // is a billion characters.
        if (decimal.scale() !in -1000..1000 || decimal.precision() > 1000) {
            throw SerializationException("not a plain decimal")
        }
        encoder.encodeString(decimal.toPlainString())
    }
}

/** Accepts `"703112"` or `703112`. */
internal object TolerantLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val primitive = json.decodeJsonElement() as? JsonPrimitive ?: return 0L
        return primitive.content.toLongOrNull() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/**
 * Wallet labels, which arrive as an **object**, not an array.
 *
 * `OnChainWalletTransactionData.Labels` and `OnChainWalletUTXOData.Labels` are
 * both `Dictionary<string, LabelData>` in BTCPay 2.x, keyed by label type. The
 * published Greenfield swagger says `"type": "array"` for both; the swagger is
 * wrong and the C# model is authoritative. A client that believes the spec
 * throws "Expected start of the array" on the first transaction that exists,
 * which takes out the whole Wallet screen rather than one row.
 *
 * Both shapes are accepted, so this keeps working if the server is ever brought
 * into line with its own documentation. The map key is used as the label text
 * only when the object omits it — the real label types all set it.
 */
internal object LabelListSerializer : KSerializer<List<LabelData>> {

    private val asList = ListSerializer(LabelData.serializer())
    private val asMap = MapSerializer(String.serializer(), LabelData.serializer())

    override val descriptor: SerialDescriptor = asList.descriptor

    override fun deserialize(decoder: Decoder): List<LabelData> {
        val json = decoder as? JsonDecoder ?: return asList.deserialize(decoder)
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> json.json.decodeFromJsonElement(asList, element)
            is JsonObject -> json.json.decodeFromJsonElement(asMap, element)
                .map { (key, label) -> if (label.text.isBlank()) label.copy(text = key) else label }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<LabelData>) =
        asList.serialize(encoder, value)
}

/**
 * Base for every API enum. An unrecognised value maps to [fallback] rather than
 * throwing, so a server upgrade cannot brick a list screen.
 *
 * The fallback is written back as JSON null, never as its own name: "Unknown"
 * is not a wire value, and inventing one would make the server reject the
 * whole save. BTCPay's store merge ignores a null and keeps its value;
 * [ApiJson.editsOf] removes such nulls from an overlay so the server's raw
 * value survives; anywhere else the server rejects the null, which is the safe
 * failure.
 */
internal abstract class FallbackEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val values: Array<T>,
    private val fallback: T,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): T {
        // Reads the element rather than `decodeString()`. With `isLenient =
        // false`, a JSON number or boolean where a quoted enum was expected
        // makes `decodeString()` throw "Expected string literal" — which fails
        // the *entire response*, so one odd row would blank a whole list. That
        // is precisely the failure this class exists to prevent, and
        // `coerceInputValues` does not help because it only rescues nulls.
        val raw = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return fallback
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback
    }

    override fun serialize(encoder: Encoder, value: T) {
        if (value == fallback && encoder is JsonEncoder) encoder.encodeJsonElement(JsonNull)
        else encoder.encodeString(value.name)
    }
}

// DTO files opt in with a file-level
// `@file:UseSerializers(BigDecimalSerializer::class)` so properties can be
// declared as plain `BigDecimal` without repeating the annotation.
