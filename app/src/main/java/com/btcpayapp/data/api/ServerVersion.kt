package com.btcpayapp.data.api

/**
 * A BTCPay Server release number, and the versions this app depends on.
 *
 * The Greenfield surface changed a lot inside 2.x: routes, models and query
 * flags appeared release by release, and an old server answers a missing route
 * with a bare 404 that says nothing about the cause. So the app states one
 * [MINIMUM], checks it at pairing (`requireSupportedServer`), and gates the few
 * features that need a newer server on the constants below, each checked
 * against the upstream release tags.
 *
 * Only `major.minor.patch` is kept. BTCPay reports a fourth assembly number
 * (`2.3.0.1`) and forks add suffixes (`2.4.1-rc1`); neither changes the API.
 */
data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0,
) : Comparable<ServerVersion> {

    override fun compareTo(other: ServerVersion): Int =
        compareValuesBy(this, other, ServerVersion::major, ServerVersion::minor, ServerVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {

        /**
         * The oldest server the app supports. `InvoiceData.paidAmount` and the
         * `rates/configuration/primary|fallback` routes first exist in 2.2.0;
         * before that every paid amount reads as 0 and the rates screen gets a
         * 404. The few newer routes the app uses are gated by the constants below.
         */
        val MINIMUM = ServerVersion(2, 2, 0)

        /**
         * `POST .../wallet/transactions/broadcast`, which the sign-then-broadcast
         * send flow needs. First in v2.3.3 (GreenfieldStoreOnChainWalletsController),
         * absent in v2.3.2.
         */
        val SIGNED_BROADCAST = ServerVersion(2, 3, 3)

        /**
         * `PUT api/v1/apps/crowdfund/{appId}`. First in v2.3.7
         * (GreenfieldAppsController); before that a crowdfund can be created but
         * not edited.
         */
        val CROWDFUND_EDIT = ServerVersion(2, 3, 7)

        /** `GET .../users/invitations`. First in v2.4.4 (GreenfieldStoreUsersController). */
        val STORE_INVITATIONS = ServerVersion(2, 4, 4)

        /** Leading digits only: at most nine per part, so `toInt` cannot overflow. */
        private val LEADING_VERSION = Regex("""^[vV]?(\d{1,9})\.(\d{1,9})(?:\.(\d{1,9}))?""")

        /**
         * Reads the leading `major.minor[.patch]` of [raw], after an optional
         * `v`. Null when there is none: an unknown version is treated as
         * supported everywhere, because refusing a server the app cannot read
         * would lock out a working setup over a cosmetic change.
         */
        fun parse(raw: String?): ServerVersion? {
            val match = LEADING_VERSION.find(raw?.trim() ?: return null) ?: return null
            val (major, minor, patch) = match.destructured
            return ServerVersion(major.toInt(), minor.toInt(), patch.toIntOrNull() ?: 0)
        }
    }
}
