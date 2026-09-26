package com.btcpayapp.core.lightning

import java.time.LocalDate
import java.util.Locale

/**
 * Turns a 33-byte node public key into something a human can read.
 *
 * Greenfield hands back only the pubkey: `GET /channels` carries `remoteNode`
 * and nothing else, and there is no endpoint anywhere in the API that will
 * resolve a peer's alias. The gossip graph knows the answer, but the only way
 * to ask it from a phone is to hand the pubkey to a third-party explorer — and
 * telling an explorer which nodes you peer with maps your node's topology for
 * whoever is watching. So this app never asks anyone.
 *
 * Three offline sources instead, in priority order:
 *
 *  1. A nickname the operator set on the device. Always wins: they know their
 *     own peers better than any list does.
 *  2. [CURATED], a short hand-verified list of the exchange and service nodes
 *     whose names carry the most weight — the ones where being wrong matters.
 *  3. The bundled table in `assets/lnnodes.bin`, six thousand aliases lifted
 *     from the public graph and searched through [NodeIndex]. Installed once by
 *     `AppGraph`; absent in a unit test, where the two layers above stand alone.
 *
 * With none of them, the pubkey is shortened rather than invented. A wrong name
 * on a channel is worse than no name: it is the pubkey, not the alias, that says
 * who you are actually connected to, and aliases are self-declared and not
 * unique — anyone may call their node "Kraken". Mapping in this direction
 * (pubkey → name, never name → pubkey) is what makes the label safe to show.
 *
 * That is also why layer 3 is filtered rather than copied wholesale. The
 * generator drops any alias claimed by more than one node unless one of them is
 * overwhelmingly the larger, drops anything that folds onto a [CURATED] name
 * from a pubkey that is not the curated one, and folds the Cyrillic and Greek
 * letters that a sans face draws identically to Latin ones. Six thousand
 * self-declared names are worth having; six thousand unchecked ones are not.
 */
object NodeDirectory {

    /**
     * Hand-verified, and consulted before the bundled table so that a name here
     * cannot be displaced by whatever the graph currently says. These are also
     * better written than the raw gossip alias — "Bitfinex (bfx-lnd0)" rather
     * than "bfx-lnd0".
     *
     * Keep it small. Everything that only needs to be *likely* right belongs in
     * the generated asset; this list is for the names that would do damage if
     * they were wrong.
     */
    private val CURATED: Map<String, String> = mapOf(
        "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f" to "ACINQ",
        "027100442c3b79f606f80f322d98d499eefcb060599efc5d4ecb00209c2cb54190" to "Block (block-iad-1)",
        "034ea80f8b148c750463546bd999bf7321a0e6dfc60aaf84bd0400a2e8d376c0d5" to "LNBiG [Hub-1]",
        "033e9ce4e8f0e68f7db49ffb6b9eecc10605f3f3fcb3c630545887749ab515b9c7" to "LNBiG [Hub-2]",
        "02c91d6aa51aa940608b497b6beebcb1aec05be3c47704b682b3889424679ca490" to "LNBiG [Hub-3]",
        "03bc9337c7a28bb784d67742ebedd30a93bacdf7e4ca16436ef3798000242b2251" to "LNBiG [Edge-2]",
        "03da1c27ca77872ac5b3e568af30673e599a47a5e4497f85c7b5da42048807b3ed" to "LNBiG [Edge-3]",
        "039edc94987c8f3adc28dab455efc00dea876089a120f573bd0b03c40d9d3fb1e1" to "LNBiG [Edge-4]",
        "03a93b87bf9f052b8e862d51ebbac4ce5e97b5f4137563cd5128548d7f5978dda9" to "cyberdyne.sh",
        "033d8656219478701227199cbd6f670335c8d408a92ae88b962c49d4dc0e83e025" to "Bitfinex (bfx-lnd0)",
        "03cde60a6323f7122d5178255766e38114b4722ede08f7c9e0c5df9b912cc201d6" to "Bitfinex (bfx-lnd1)",
        "03a1f3afd646d77bdaf545cceaf079bab6057eae52c6319b63b5803d0989d6a72f" to "Binance",
        "0294ac3e099def03c12a37e30fe5364b1223fd60069869142ef96580c8439c2e0a" to "OKX",
        "02437c00ef5de2686a6bd60f8acb5c83d17010916010a15f479d5ef84c04f04485" to "Kraken",
        "03c8e5f583585cac1de2b7503a6ccd3c12ba477cfd139cd4905be504c2f48e86bd" to "Strike",
        "037f990e61acee8a7697966afd29dd88f3b1f8a7b14d625c4f8742bd952003a590" to "FixedFloat",
        "0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3" to "CoinGate",
        "021c97a90a411ff2b10dc2a8e32de2f29d2fa49d41bfbb52bd416e460db0747d0d" to "Lightning Labs (LOOP)",
        "03abf6f44c355dec0d5aa155bdbdd6e0c8fefe318eff402de65c6eb2e1be55dc3e" to "OpenNode 1",
        "028d98b9969fbed53784a36617eb489a59ab6dc9b9d77fcdca9ff55307cd98e3c4" to "OpenNode 2",
        "030bd936b47a53af5eba629529dff8b2222aa9ae0ed2c12476b2bcf28bc996b8a6" to "Bitrefill A",
        "02757afbc23eafd8c8ffb02fbf92c8ee6bcceb29fbff4d12087f8191a49dc57ef4" to "BitGo",
        "037659a0ac8eb3b8d0a720114efc861d3a940382dcfa1403746b4f8f6b2e8810ba" to "NiceHash",
        "028b10be90ddfb4ef158c252163e4f340c8a093beee20427d39a0986abac58a55e" to "Paxful",
        "02c197ffa4c2aa4105dd4c4b7279ba1b9061b22910ebbfa759b0001bed9ee48a16" to "BC.GAME",
        "02dfb4c1dd59216fa6a28d0f012e188516f63517db68c4e4b82c3af41343a05bc4" to "Blink",
        "028116bb258c7604009cedbb01899621271bd874935fa7eba3843350c8b47c8388" to "LN Markets",
        "027cd974e47086291bb8a5b0160a889c738f2712a703b8ea939985fd16f3aae67e" to "Zap (lndus0)",
        "03b428ba4b48b524f1fa929203ddc2f0971c2077c2b89bb5b22fd83ed82ac2f7e1" to "Zap (lndus1)",
        "0364913d18a19c671bb36dd04d6ad5be0fe8f2894314c36a9db3f03c2d414907e1" to "LQWD Canada",
        "026af41af0e3861ba170cc0eef8f45a1015125dac57c28df53752dcaeea793b28f" to "BitcoinVN 22",
        "038a9e56512ec98da2b5789761f7af8f280baf98a09282360cd6ff1381b5e889bf" to "Megalith LSP",
        "0322d0e43b3d92d30ed187f4e101a9a9605c3ee5fc9721e6dac3ce3d7732fbb13e" to "Megalithic.me",
        "038ba8f67ba8ff5c48764cdd3251c33598d55b203546d08a8f0ec9dcd9f27e3637" to "flashsats.xyz",
        "02e4971e61a3f55718ae31e2eed19aaf2e32caf3eb5ef5ff03e01aa3ada8907e78" to "1sats.com",
        "0326e692c455dd554c709bbb470b0ca7e0bb04152f777d1445fd0bf3709a2833a3" to "allNice / torq.co",
        "03ccc570ec6aaff08d5435b3413f4b4af8175728a1ed244e4710121c8f5af6ea07" to "LNT.Thailand",
        "0217890e3aad8d35bc054f43acc00084b25229ecff0ab68debd82883ad65ee8266" to "1ML node ALPHA",
        "0288be11d147e1525f7f234f304b094d6627d2c70f3313d7ba3696887b261c4447" to "yalls.org",
        "0298f6074a454a1f5345cb2a7c6f9fce206cd0bf675d177cdbf0ca7508dd28852f" to "BCash_Is_Trash",
        "0324ba2392e25bff76abd0b1f7e4b53b5f82aa53fddc3419b051b6c801db9e2247" to "kappa",
        "03b62dd177f6a8bd72b29a4d4d2b2eaa7fce14384c1d05e78a569a27452f20162b" to "Nova",
        "02b21730bc36061609cc1fe1bd7f5d3068e0a5e511aaf210a82c046b58020c4aa8" to "Tachyon",
        "03c157946cc1cd376b929e36006e645fae490b1b1d4156b40db804e01b4bda48cd" to "The Continental",
        "03423790614f023e3c0cdaa654a3578e919947e4c3a14bf5044e7c787ebd11af1a" to "Sunny Sarah",
        "03632f5c3e45832ddf045469f117b503764ad71866d961ae5ec8df2c4378ac019a" to "Julia LND01",
        "02f31f23d87a366e5ed28f72546193f85cba1073b7386eafd53b0d349df79ee6f1" to "Uncle Jim's Node",
        "0391904d140fdf88d19423513945a5fcc49c606521b65a85f6d6fe46ebdd1c7665" to "LifeIsGood",
        "0379dbd35a22abe30d87f89664bad7aea31e4ba15a2e69ec4946113cfb9843c445" to "points-nexus",
        "02e9046555a9665145b0dbd7f135744598418df7d61d3660659641886ef1274844" to "SilentBob",
        "03d32e5618df518e911e2c0bf4686e9d236ae9ab0afd442f0a5de28b74d64338b3" to "Vix18",
        "03e9c99fddf5aaa60e22c166622206947b1fb14ae2926f550f837af6aa83556bb8" to "Shockwave Rider",
        "024c08d7cded0993167094b1b941ec58ff594951c94094e916220f26dc2055f5ea" to "Coinduit",
        "0243bf62e9cbe219c1fc61c757e1b694774a377d9c6b97fc3df2352f975402f0dc" to "Socrates",
        "0340cfadaa3324e0dd176a9969be050114278f93260e1b6333bd2a2a2ea03c64a3" to "Babylon-4a",
        "02cad4ec4c8b0dc2c7035a1898f979c8c7169bdff74e01ad6ca7aea59d85c59e8b" to "xmrk reloaded",
        "03223cf7709a22ade0e34764e7222ffa792fbbba2878a75b84705545f3726021f6" to "LNL",
        "022d73890548be3625f822f0294d1c30934c6063df8904bafc70224920e14f10e7" to "LightningPlatinum",
        "03e875c708839f5c19cc2289c8eca9f02b9f3ab933e30a37a3cb28993b13663d57" to "Medium of Exchange",
        "0206db3b9c7475b246d380987bb77cf55acb65e2b0913b6e7d97063efd6d3d3d95" to "Open_Hand",
        "03e95b4144147cbb8f5009bf32d48314738135d02c28260c9d6f8a74454fb6dc5f" to "Sally Acorn",
        "03d6749842cabfbfbe386b73d2e34294aef89fb816e40e59e42109d830ff5ee89d" to "Satoshi 17",
        "026f46207fd290a33cbd86e29b3ad0a47cdd44ab9aa5267cde66483e10aa9d3180" to "Authenticity",
        "034b3ecd8d87edf1e13aa23cb34c4f719d2faeeeae10010d44d91f5e432c8e4b5e" to "PaidlyInteractive2",
        "0272bafa59999de0536104984c8ee970ecc4b1a57e584555f3181a3ce6b8fae7cd" to "BIG",
    )

    private val HEX_66 = Regex("^[0-9a-f]{66}$")

    /**
     * The generated table, installed once at startup and never replaced after.
     *
     * A settable singleton rather than a constructor argument because the
     * alternative is threading an instance through every Composable that draws a
     * peer — a channel row, a peer sheet, an invoice preview — to give all of
     * them the same value. Volatile so the install is visible to whichever
     * thread reads first; null until then, and null forever in a unit test,
     * which is a supported state rather than a failure.
     */
    @Volatile
    private var bundled: NodeIndex? = null

    /** Called once, by `AppGraph`. */
    fun install(index: NodeIndex?) {
        bundled = index
    }

    /**
     * Strips a `@host:port` suffix and lowercases, so a value taken from a node
     * URI and one taken from a channel row resolve to the same key.
     */
    fun normalise(value: String): String =
        value.trim().substringBefore('@').lowercase(Locale.ROOT)

    fun isPubkey(value: String): Boolean = HEX_66.matches(normalise(value))

    /** The curated or bundled name, or null. Nicknames are applied by [label]. */
    fun wellKnownName(pubkey: String): String? {
        val key = normalise(pubkey)
        return CURATED[key] ?: bundled?.name(key)
    }

    /**
     * What to draw for a peer: nickname, then bundled name, then a shortened
     * pubkey. Never null — every channel row needs something in the title slot.
     */
    fun label(pubkey: String, nicknames: Map<String, String> = emptyMap()): String {
        val key = normalise(pubkey)
        if (key.isEmpty()) return "Unknown peer"
        nicknames[key]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        wellKnownName(key)?.let { return it }
        return short(key)
    }

    /** True when [label] returned a name rather than a shortened key. */
    fun isNamed(pubkey: String, nicknames: Map<String, String> = emptyMap()): Boolean {
        val key = normalise(pubkey)
        return nicknames[key]?.isNotBlank() == true || wellKnownName(key) != null
    }

    /** `03864ef0…97a3f8f` — enough to compare against another screen by eye. */
    fun short(pubkey: String): String {
        val key = normalise(pubkey)
        return if (key.length <= 20) key else "${key.take(8)}…${key.takeLast(7)}"
    }

    /** Every name the app can produce without being told one. */
    val size: Int get() = CURATED.size + (bundled?.size ?: 0)

    /** The date the bundled table was generated, or null if it failed to load. */
    val generatedAt: LocalDate? get() = bundled?.generatedAt
}
