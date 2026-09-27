package com.btcpayapp.ui.screens.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Server and store SMTP saves share [smtpSave]. A new host or login decides
 * who can read the mail, and the server's mail carries password-reset links,
 * so such a save must be reviewed. BTCPay keeps the stored password for any
 * host, so a new host must not get it by default.
 */
class SmtpRouteTest {

    private val saved = SmtpRoute.of("smtp.example.com", "587", "shop@example.com", skipCertificateCheck = false)

    private fun check(route: SmtpRoute, passwordTyped: Boolean = false, passwordSet: Boolean = true) =
        smtpSave(saved, route, passwordTyped, passwordSet)

    @Test
    fun `the same route saves at once, also retyped with other spacing and case`() {
        assertEquals(SmtpSave.Go, check(saved))
        assertEquals(SmtpSave.Go, check(SmtpRoute.of(" SMTP.Example.com ", "587 ", " shop@example.com", false)))
    }

    @Test
    fun `a new host with a login needs the password typed again when one is stored`() {
        val route = SmtpRoute.of("smtp.other.example", "587", "shop@example.com", false)
        assertEquals(SmtpSave.RetypePassword, check(route))
        assertEquals(SmtpSave.Review, check(route, passwordTyped = true))
        assertEquals(SmtpSave.Review, check(route, passwordSet = false))
    }

    @Test
    fun `a new host without a login is reviewed, as the server then drops the password`() {
        assertEquals(SmtpSave.Review, check(SmtpRoute.of("smtp.other.example", "587", "", false)))
    }

    @Test
    fun `a new login or port on the same host is reviewed`() {
        assertEquals(SmtpSave.Review, check(SmtpRoute.of("smtp.example.com", "587", "someone@example.com", false)))
        assertEquals(SmtpSave.Review, check(SmtpRoute.of("smtp.example.com", "2525", "shop@example.com", false)))
    }

    @Test
    fun `turning the certificate check off is reviewed, turning it back on is not`() {
        assertEquals(SmtpSave.Review, check(saved.copy(skipCertificateCheck = true)))
        val unchecked = saved.copy(skipCertificateCheck = true)
        assertEquals(SmtpSave.Go, smtpSave(unchecked, saved, passwordTyped = false, passwordSet = true))
    }

    @Test
    fun `clearing the host sends no mail, so it saves at once`() {
        assertEquals(SmtpSave.Go, check(SmtpRoute.of(" ", "587", "shop@example.com", true)))
    }

    @Test
    fun `a first setup is reviewed`() {
        assertEquals(SmtpSave.Review, smtpSave(SmtpRoute(), saved, passwordTyped = true, passwordSet = false))
    }
}
