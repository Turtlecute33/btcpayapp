package com.btcpayapp.ui.screens.onboarding

import com.btcpayapp.core.pairing.Pairing
import com.btcpayapp.data.session.Permissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The access choice decides what a new key can do, and the paste screen
 * compares what a key got with what was chosen. The default must never be
 * able to move funds, and the compare must follow the server's
 * policy tree, or it warns about a 2.3 key that is fine and misses one that
 * can do more. So both are tested as the functions the screens call.
 */
class AccessChoiceTest {

    // --- permissionsFor ----------------------------------------------------

    @Test
    fun `take payments is first, the default, and asks for the point of sale set`() {
        assertEquals(PermissionSet.TakePayments, PermissionSet.entries.first())
        assertEquals(PermissionSet.TakePayments, PairState().permissionSet)
        assertEquals(PermissionSet.TakePayments, ManualKeyState().access)
        assertEquals(Pairing.POINT_OF_SALE_PERMISSIONS, permissionsFor(PermissionSet.TakePayments, serverAdmin = false))
    }

    @Test
    fun `each set asks for its own list`() {
        assertEquals(Pairing.DEFAULT_PERMISSIONS, permissionsFor(PermissionSet.Full, serverAdmin = false))
        assertEquals(Pairing.READ_ONLY_PERMISSIONS, permissionsFor(PermissionSet.ReadOnly, serverAdmin = false))
    }

    @Test
    fun `server permissions are added only when asked`() {
        PermissionSet.entries.forEach { set ->
            val plain = permissionsFor(set, serverAdmin = false)
            assertTrue(set.name, plain.none { it.startsWith("btcpay.server.") })
        }
        assertEquals(
            Pairing.DEFAULT_PERMISSIONS + Pairing.SERVER_ADMIN_PERMISSIONS,
            permissionsFor(PermissionSet.Full, serverAdmin = true),
        )
    }

    @Test
    fun `server permissions never join take payments or watch only`() {
        // They pay from the server's Lightning node and can create an
        // administrator, which would make both cards' promises false.
        listOf(PermissionSet.TakePayments, PermissionSet.ReadOnly).forEach { set ->
            assertEquals(set.name, permissionsFor(set, serverAdmin = false), permissionsFor(set, serverAdmin = true))
        }
    }

    @Test
    fun `take payments covers no spend, store settings or webhook policy`() {
        val pos = permissionsFor(PermissionSet.TakePayments, serverAdmin = false)
        assertTrue(Permissions.covers(pos, "${STORE}cancreateinvoice"))
        assertTrue(Permissions.covers(pos, "${STORE}cancreatelightninginvoice"))
        val forbidden = listOf(
            "${STORE}canmodifystoresettings",
            "${STORE}canmanagewallets",
            "${STORE}canmanagewallettransactions",
            "${STORE}cancreatetransactions",
            "${STORE}cansigntransactions",
            "${STORE}canbroadcasttransactions",
            "${STORE}canuselightningnode",
            "${STORE}canmanagepayouts",
            "${STORE}canmanagepullpayments",
            "${STORE}webhooks.canmodifywebhooks",
        )
        assertEquals(emptyList<String>(), forbidden.filter { Permissions.covers(pos, it) })
    }

    @Test
    fun `an unknown access name falls back to the least access`() {
        assertEquals(PermissionSet.Full, permissionSetNamed("Full"))
        assertEquals(PermissionSet.ReadOnly, permissionSetNamed("ReadOnly"))
        assertEquals(PermissionSet.TakePayments, permissionSetNamed("TakePayments"))
        assertEquals(PermissionSet.TakePayments, permissionSetNamed("Unrestricted"))
        assertEquals(PermissionSet.TakePayments, permissionSetNamed(""))
    }

    // --- permissionDiff ----------------------------------------------------

    @Test
    fun `store suffixes are ignored`() {
        val requested = permissionsFor(PermissionSet.TakePayments, serverAdmin = false)
        val granted = requested.map { "$it:store-a" } + requested.map { "$it:store-b" }
        assertEquals(NOTHING, permissionDiff(requested, granted))
    }

    @Test
    fun `a 2_3 key holding store settings lacks nothing a full request asked for`() {
        // BTCPay 2.3 does not know the 2.4 wallet policies and drops them from
        // the grant, but its canmodifystoresettings covers every one of them.
        val walletPolicies = setOf(
            "${STORE}canviewwallet",
            "${STORE}canmanagewallettransactions",
            "${STORE}cancreatetransactions",
            "${STORE}cansigntransactions",
            "${STORE}canbroadcasttransactions",
        )
        val requested = permissionsFor(PermissionSet.Full, serverAdmin = false)
        val granted = requested.filterNot { it in walletPolicies }.map { "$it:store-a" }
        assertTrue(granted.contains("${STORE}canmodifystoresettings:store-a"))
        assertEquals(NOTHING, permissionDiff(requested, granted))
    }

    @Test
    fun `store settings on a take payments request is extra`() {
        val requested = permissionsFor(PermissionSet.TakePayments, serverAdmin = false)
        val granted = Pairing.DEFAULT_PERMISSIONS.map { "$it:store-a" }
        val (missing, extra) = permissionDiff(requested, granted)
        assertEquals(emptyList<String>(), missing)
        assertTrue(extra.toString(), "${STORE}canmodifystoresettings" in extra)
        assertTrue(extra.toString(), "${STORE}cansigntransactions" in extra)
        // Covered by the request, so not extra.
        assertTrue(extra.toString(), "${STORE}cancreateinvoice" !in extra)
    }

    @Test
    fun `a narrower key reports what it lacks`() {
        val requested = permissionsFor(PermissionSet.Full, serverAdmin = true)
        val granted = Pairing.POINT_OF_SALE_PERMISSIONS
        val (missing, extra) = permissionDiff(requested, granted)
        assertEquals(emptyList<String>(), extra)
        assertTrue(missing.toString(), "${STORE}cansigntransactions" in missing)
        assertTrue(missing.toString(), "btcpay.server.canmodifyserversettings" in missing)
        assertTrue(missing.toString(), "${STORE}cancreateinvoice" !in missing)
    }

    @Test
    fun `an unrestricted key lacks nothing and says so as extra`() {
        val (missing, extra) = permissionDiff(permissionsFor(PermissionSet.ReadOnly, serverAdmin = false), listOf("unrestricted"))
        assertEquals(emptyList<String>(), missing)
        assertEquals(listOf("unrestricted"), extra)
    }

    @Test
    fun `a key that did not report its permissions shows no difference`() {
        assertEquals(NOTHING, permissionDiff(permissionsFor(PermissionSet.Full, serverAdmin = false), emptyList()))
    }

    private companion object {
        const val STORE = "btcpay.store."
        val NOTHING = emptyList<String>() to emptyList<String>()
    }
}
