package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.CreateStoreRequest
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.LightningAddressData
import com.btcpayapp.data.api.dto.LightningPayoutProcessorSettings
import com.btcpayapp.data.api.dto.OnChainPayoutProcessorSettings
import com.btcpayapp.data.api.dto.OnChainWalletConfig
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.dto.RefundInvoiceRequest
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.dto.StoreRateConfiguration
import com.btcpayapp.data.api.dto.StoreUserData
import com.btcpayapp.data.api.dto.UpdateLightningPayoutProcessorSettings
import com.btcpayapp.data.api.dto.UpdateOnChainPayoutProcessorSettings
import com.btcpayapp.data.api.dto.WalletTransactionData
import com.btcpayapp.data.api.dto.forWrite
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * BTCPay's wire format has several traps that a generated client walks straight
 * into. Each test below pins one of them.
 */
class SerializationTest {

    private val json = ApiJson.instance

    @Test
    fun `decimals arrive as strings and stay exact`() {
        val body = """
            {"id":"inv1","storeId":"s1","amount":"1234.56","paidAmount":"0.00",
             "currency":"USD","status":"Settled","createdTime":1710000000}
        """.trimIndent()

        val invoice = json.decodeFromString(InvoiceData.serializer(), body)

        assertEquals(BigDecimal("1234.56"), invoice.amount)
        assertEquals(InvoiceStatus.Settled, invoice.status)
    }

    @Test
    fun `decimals sent as numbers are also accepted`() {
        // The server is inconsistent: paymentTolerance and feeRate are plain
        // numbers while everything else is a string.
        val body = """{"id":"inv1","amount":1234.56,"currency":"USD"}"""
        val invoice = json.decodeFromString(InvoiceData.serializer(), body)
        assertEquals(BigDecimal("1234.56"), invoice.amount)
    }

    @Test
    fun `an unknown status degrades instead of throwing`() {
        // A server upgrade that adds a status must not break the list screen.
        val body = """{"id":"inv1","status":"SomeFutureStatus","currency":"USD"}"""
        val invoice = json.decodeFromString(InvoiceData.serializer(), body)
        assertEquals(InvoiceStatus.Unknown, invoice.status)
    }

    @Test
    fun `unknown fields are ignored`() {
        val body = """{"id":"inv1","currency":"USD","somethingAddedIn2027":{"nested":true}}"""
        val invoice = json.decodeFromString(InvoiceData.serializer(), body)
        assertEquals("inv1", invoice.id)
    }

    @Test
    fun `wallet transaction block height and confirmations arrive as strings`() {
        val body = """
            {"transactionHash":"aa","amount":"-0.0005","blockHeight":"832145",
             "confirmations":"6","timestamp":1710000000,"status":"Confirmed"}
        """.trimIndent()

        val tx = json.decodeFromString(WalletTransactionData.serializer(), body)

        assertEquals(832145L, tx.blockHeight)
        assertEquals(6L, tx.confirmations)
        assertEquals(BigDecimal("-0.0005"), tx.amount)
        assertTrue(!tx.isIncoming)
    }

    @Test
    fun `payment method criteria is an array despite the published schema`() {
        val body = """
            {"id":"s1","name":"Shop","defaultCurrency":"EUR",
             "paymentMethodCriteria":[{"paymentMethodId":"BTC-LN","currencyCode":"EUR",
             "amount":"100.00","above":true}]}
        """.trimIndent()

        val store = json.decodeFromString(StoreData.serializer(), body)

        assertEquals(1, store.paymentMethodCriteria.size)
        assertEquals("BTC-LN", store.paymentMethodCriteria.first().paymentMethodId)
        assertEquals(BigDecimal("100.00"), store.paymentMethodCriteria.first().amount)
    }

    @Test
    fun `payment tolerance is a plain number, not a string`() {
        val body = """{"id":"s1","name":"Shop","paymentTolerance":2.5}"""
        val store = json.decodeFromString(StoreData.serializer(), body)
        assertEquals(2.5, store.paymentTolerance, 0.0001)
    }

    @Test
    fun `payout amount is null until approval`() {
        val body = """
            {"id":"p1","revision":0,"date":1710000000,"destination":"bc1q",
             "originalCurrency":"USD","originalAmount":"50.00",
             "payoutMethodId":"BTC-CHAIN","state":"AwaitingApproval"}
        """.trimIndent()

        val payout = json.decodeFromString(PayoutData.serializer(), body)

        assertEquals(PayoutState.AwaitingApproval, payout.state)
        assertEquals(null, payout.payoutAmount)
    }

    @Test
    fun `encoding omits nulls so a PUT does not wipe untouched settings`() {
        // BTCPay treats an absent field as "leave unchanged" on several
        // endpoints, so explicit nulls would silently clear configuration.
        val request = com.btcpayapp.data.api.dto.UpdatePaymentMethodRequest(enabled = true)
        val encoded = json.encodeToString(
            com.btcpayapp.data.api.dto.UpdatePaymentMethodRequest.serializer(),
            request,
        )
        assertEquals("""{"enabled":true}""", encoded)
    }

    @Test
    fun `decimals are written back as strings`() {
        val request = com.btcpayapp.data.api.dto.CreateInvoiceRequest(
            amount = BigDecimal("19.99"),
            currency = "USD",
        )
        val encoded = json.encodeToString(
            com.btcpayapp.data.api.dto.CreateInvoiceRequest.serializer(),
            request,
        )
        assertTrue(encoded.contains(""""amount":"19.99""""))
    }

    // -----------------------------------------------------------------------
    // Fields the swagger types wrongly, and bodies the server validates
    // -----------------------------------------------------------------------

    @Test
    fun `rate spread arrives as a JSON number and is kept as its text`() {
        val body = """{"spread":0.0,"preferredSource":"kraken","isCustomScript":false,"effectiveScript":"X_X = kraken(X_X);"}"""
        val config = json.decodeFromString(StoreRateConfiguration.serializer(), body)
        assertEquals("0.0", config.spread)
    }

    @Test
    fun `a rates write without a custom script sends an empty script and keeps the source`() {
        // The GET always fills effectiveScript; echoing it back is rejected.
        val loaded = StoreRateConfiguration(
            spread = "0.5",
            preferredSource = "kraken",
            isCustomScript = false,
            effectiveScript = "X_X = kraken(X_X);",
        )
        val sent = encodedObject(StoreRateConfiguration.serializer(), loaded.forWrite())
        assertEquals("", sent.getValue("effectiveScript").jsonPrimitive.content)
        assertEquals("kraken", sent.getValue("preferredSource").jsonPrimitive.content)
        assertEquals("0.5", sent.getValue("spread").jsonPrimitive.content)
    }

    @Test
    fun `a rates write with a custom script drops the preferred source`() {
        val loaded = StoreRateConfiguration(
            preferredSource = "kraken",
            isCustomScript = true,
            effectiveScript = "BTC_USD = coingecko(BTC_USD);",
        )
        val written = loaded.forWrite()
        assertNull(written.preferredSource)
        assertEquals("BTC_USD = coingecko(BTC_USD);", written.effectiveScript)
        assertFalse(encodedObject(StoreRateConfiguration.serializer(), written).containsKey("preferredSource"))
    }

    @Test
    fun `lightning address limits arrive as JSON numbers`() {
        val body = """[{"username":"shop","min":1000.0,"max":50000,"currencyCode":null}]"""
        val address = json.decodeFromString(ListSerializer(LightningAddressData.serializer()), body).single()
        assertEquals("1000.0", address.min)
        assertEquals("50000", address.max)
    }

    @Test
    fun `on-chain processor fee target is read as feeBlockTarget and as the swagger spelling`() {
        val wire = """{"payoutMethodId":"BTC-CHAIN","feeBlockTarget":6,"intervalSeconds":3600,"threshold":"0.001"}"""
        assertEquals(6, json.decodeFromString(OnChainPayoutProcessorSettings.serializer(), wire).feeTargetBlock)

        val legacy = """{"payoutMethodId":"BTC-CHAIN","feeTargetBlock":3}"""
        assertEquals(3, json.decodeFromString(OnChainPayoutProcessorSettings.serializer(), legacy).feeTargetBlock)
    }

    @Test
    fun `on-chain processor update writes feeBlockTarget`() {
        val sent = encodedObject(
            UpdateOnChainPayoutProcessorSettings.serializer(),
            UpdateOnChainPayoutProcessorSettings(feeTargetBlock = 6, intervalSeconds = 3600, threshold = BigDecimal("0.001")),
        )
        assertEquals(6, sent.getValue("feeBlockTarget").jsonPrimitive.content.toInt())
        assertFalse(sent.containsKey("feeTargetBlock"))
        assertEquals("0.001", sent.getValue("threshold").jsonPrimitive.content)
    }

    @Test
    fun `lightning processor settings never send cancelPayoutAfterFailures`() {
        // The server has no such field; a value the user typed must not look saved.
        val update = encodedObject(
            UpdateLightningPayoutProcessorSettings.serializer(),
            UpdateLightningPayoutProcessorSettings(intervalSeconds = 60),
        )
        assertFalse(update.containsKey("cancelPayoutAfterFailures"))
        // A server that does send it is still read.
        val read = json.decodeFromString(
            LightningPayoutProcessorSettings.serializer(),
            """{"payoutMethodId":"BTC-LN","intervalSeconds":60,"cancelPayoutAfterFailures":3}""",
        )
        assertEquals(60, read.intervalSeconds)
    }

    @Test
    fun `creating a store sends only name and currency`() {
        // Anything else sent would override the admin's default store template.
        val sent = encodedObject(CreateStoreRequest.serializer(), CreateStoreRequest("Shop", "EUR"))
        assertEquals(setOf("name", "defaultCurrency"), sent.keys)
        assertEquals("Shop", sent.getValue("name").jsonPrimitive.content)
        assertEquals("EUR", sent.getValue("defaultCurrency").jsonPrimitive.content)
    }

    @Test
    fun `a refund names its method in both the new and the legacy field`() {
        val sent = encodedObject(
            RefundInvoiceRequest.serializer(),
            RefundInvoiceRequest(payoutMethods = listOf("BTC-CHAIN"), payoutMethodId = "BTC-CHAIN"),
        )
        assertEquals(listOf("BTC-CHAIN"), sent.getValue("payoutMethods").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("BTC-CHAIN", sent.getValue("payoutMethodId").jsonPrimitive.content)
    }

    @Test
    fun `an on-chain config reads the DerivationSchemeSettings shape the server returns`() {
        val body = """
            {"accountDerivation":"xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz",
             "accountOriginal":null,
             "accountKeySettings":[{"rootFingerprint":"73c5da0a","accountKeyPath":"84'/0'/0'",
               "accountKey":"xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"}],
             "isHotWallet":true,"source":"NBXplorer","label":"Shop wallet","isMultiSigOnServer":false}
        """.trimIndent()

        val config = json.decodeFromString(OnChainWalletConfig.serializer(), body)

        assertTrue(config.accountDerivation.startsWith("xpub6CUGRUon"))
        assertTrue(config.isHotWallet)
        assertEquals("NBXplorer", config.source)
        assertEquals("Shop wallet", config.label)
        val key = config.accountKeySettings.single()
        assertEquals("73c5da0a", key.rootFingerprint)
        assertEquals("84'/0'/0'", key.accountKeyPath)
        assertEquals(config.accountDerivation, key.accountKey)
    }

    @Test
    fun `a store user's role is read under both the old and the new key`() {
        val users = ListSerializer(StoreUserData.serializer())
        // 2.2.0 to 2.4.3 send storeRole (plus a legacy role); 2.4.4 sends roleId.
        val older = """[{"id":"u1","email":"a@example.org","storeRole":"Owner","role":"Owner","userId":"u1"}]"""
        val newer = """[{"id":"u1","email":"a@example.org","roleId":"Guest"}]"""
        assertEquals("Owner", json.decodeFromString(users, older).single().roleId)
        assertEquals("Guest", json.decodeFromString(users, newer).single().roleId)
    }

    private fun <T> encodedObject(serializer: KSerializer<T>, value: T): JsonObject =
        json.parseToJsonElement(json.encodeToString(serializer, value)).jsonObject
}
