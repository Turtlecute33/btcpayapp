package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.dto.WalletTransactionData
import org.junit.Assert.assertEquals
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
}
