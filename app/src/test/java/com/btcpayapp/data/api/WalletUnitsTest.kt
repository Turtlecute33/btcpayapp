package com.btcpayapp.data.api

import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.screens.wallet.parseAmountToBtc
import com.btcpayapp.ui.screens.wallet.unitLabel
import org.junit.Assert.*
import org.junit.Test

class WalletUnitsTest {
    @Test fun `Bitcoin sat preference does not divide Litecoin amounts`() {
        assertEquals(0, java.math.BigDecimal("1.5").compareTo(parseAmountToBtc("1.5", BitcoinUnit.Sat, "LTC")))
        assertEquals("LTC", unitLabel(BitcoinUnit.Sat, "LTC"))
    }
    @Test fun `Bitcoin amounts continue to convert from satoshi`() {
        assertEquals(0, java.math.BigDecimal("0.00000001").compareTo(parseAmountToBtc("1", BitcoinUnit.Sat, "BTC")))
    }
    @Test fun `onchain Bitcoin amounts never round fractional satoshi`() {
        assertNull(parseAmountToBtc("0.6", BitcoinUnit.Sat, "BTC"))
        assertNull(parseAmountToBtc("0.000000006", BitcoinUnit.Btc, "BTC"))
    }
}
