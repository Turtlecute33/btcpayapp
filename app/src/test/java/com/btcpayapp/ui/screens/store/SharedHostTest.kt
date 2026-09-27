package com.btcpayapp.ui.screens.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedHostTest {

    @Test
    fun `a shared host with no wallet id names no account`() {
        // One public host serves many accounts, and the key that picks one is not shown.
        assertFalse(connectionNamesAccount("type=lnbits;server=https://legend.lnbits.com;api-key=k"))
        assertFalse(connectionNamesAccount("type=LNDhub;server=https://login:password@lndhub.io"))
        assertFalse(connectionNamesAccount("type=blink;server=https://api.blink.sv/graphql;api-key=k"))
    }

    @Test
    fun `a wallet id or a node's own host names the account`() {
        assertTrue(connectionNamesAccount("type=blink;server=https://api.blink.sv/graphql;api-key=k;wallet-id=w"))
        assertTrue(connectionNamesAccount("type=lnd-rest;server=https://node.example:8080/;macaroon=m"))
        assertTrue(connectionNamesAccount("type=clightning;server=tcp://10.0.0.2:9835/"))
    }
}
