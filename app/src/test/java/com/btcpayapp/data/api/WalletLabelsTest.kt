package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.WalletTransactionData
import com.btcpayapp.data.api.dto.WalletUtxoData
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of `labels`.
 *
 * BTCPay 2.x declares `OnChainWalletTransactionData.Labels` and
 * `OnChainWalletUTXOData.Labels` as `Dictionary<string, LabelData>` and
 * initialises both, so the key is emitted on every row — as a JSON **object**.
 * The published Greenfield swagger says `"type": "array"` for both, so DTOs
 * written from the swagger cannot parse the Wallet or Coins responses once a
 * store has one transaction.
 *
 * `SerializationTest` cannot catch this because its fixture omits `labels`
 * entirely, which lets the Kotlin default stand in. These use real response
 * bodies.
 */
class WalletLabelsTest {

    private val json = ApiJson.instance

    /** What BTCPay returns for a wallet with no labels set: the key is still there. */
    @Test
    fun `transaction with an empty labels object parses`() {
        val body = """
            [{"transactionHash":"a1","comment":"","labels":{},"amount":"-0.0005",
              "blockHash":"b1","blockHeight":703112,"confirmations":6,
              "timestamp":1592312018,"status":"Confirmed"}]
        """.trimIndent()

        val rows = json.decodeFromString(ListSerializer(WalletTransactionData.serializer()), body)

        assertEquals(1, rows.size)
        assertTrue(rows.first().labels.isEmpty())
        assertEquals("a1", rows.first().transactionHash)
    }

    @Test
    fun `transaction with populated labels object parses and keeps the text`() {
        val body = """
            [{"transactionHash":"a2","comment":"rent","amount":"0.25",
              "blockHeight":703113,"confirmations":3,"timestamp":1592312019,
              "status":"Confirmed",
              "labels":{"invoice":{"type":"invoice","text":"invoice","ref":"9Fo"},
                        "paid":{"type":"raw","text":"paid"}}}]
        """.trimIndent()

        val labels = json.decodeFromString(ListSerializer(WalletTransactionData.serializer()), body)
            .first().labels

        assertEquals(2, labels.size)
        assertEquals(setOf("invoice", "paid"), labels.map { it.text }.toSet())
        assertEquals(setOf("invoice", "raw"), labels.map { it.type }.toSet())
    }

    /** The same field on the Coins screen, which has the same shape. */
    @Test
    fun `utxo with a labels object parses`() {
        val body = """
            [{"comment":"","amount":"0.01","outpoint":"a3:0","timestamp":1592312020,
              "keyPath":"0/1","address":"bc1qexample","confirmations":12,
              "labels":{"payout":{"type":"payout","text":"payout"}}}]
        """.trimIndent()

        val utxos = json.decodeFromString(ListSerializer(WalletUtxoData.serializer()), body)

        assertEquals(1, utxos.size)
        assertEquals(listOf("payout"), utxos.first().labels.map { it.text })
    }

    /**
     * The swagger's array form still has to work: if BTCPay is ever corrected to
     * match its own documentation, this client must not break the other way.
     */
    @Test
    fun `the documented array form still parses`() {
        val body = """
            [{"transactionHash":"a4","amount":"1.0","timestamp":1,"status":"Confirmed",
              "labels":[{"type":"raw","text":"manual"}]}]
        """.trimIndent()

        val labels = json.decodeFromString(ListSerializer(WalletTransactionData.serializer()), body)
            .first().labels

        assertEquals(listOf("manual"), labels.map { it.text })
    }

    /** A label object that omits `text` falls back to its map key rather than rendering blank. */
    @Test
    fun `a label with no text falls back to the key`() {
        val body = """
            [{"transactionHash":"a5","amount":"1.0","timestamp":1,"status":"Confirmed",
              "labels":{"payment-request":{"type":"app"}}}]
        """.trimIndent()

        val labels = json.decodeFromString(ListSerializer(WalletTransactionData.serializer()), body)
            .first().labels

        assertEquals(listOf("payment-request"), labels.map { it.text })
    }
}
