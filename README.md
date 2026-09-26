# BtcPayServer for Android

A native Android client for [BTCPay Server](https://btcpayserver.org). Not a
WebView wrapper — every screen is Jetpack Compose talking to the Greenfield API
directly.

Built for the person standing behind the counter: a real payment terminal, live
invoice tracking, on-chain and Lightning wallets, payouts, store and server
administration, and a security posture that assumes the phone will eventually be
lost.

---

## What it does

**Taking money**
- Point-of-sale terminal: keypad, currency switching, optional tips, one tap to
  charge.
- Live checkout screen with a QR, an expiry countdown and automatic status
  polling; celebrates on settlement.
- Full invoice list with search, status filters and infinite scroll.
- Invoice detail: payment methods, individual payments, order metadata, manual
  status marking, archive, refunds.
- Payment requests and crowdfunds; Point-of-Sale app editing including the item
  template.

**Moving money**
- One wallet screen for both rails: a single total, the on-chain and Lightning
  split underneath, and history behind an On-chain / Lightning tab pair. Receive
  acts on whichever tab is open.
- One Send screen for both rails, which decides between them from what you paste
  — see [One Send](#one-send).
- On-chain wallet: balances, transaction history with labels and comments, UTXO
  list with coin control, address reservation, fee-rate targets, RBF.
- Watch-only wallets are recognised rather than discovered at the end of a send
  form: an API key without signing rights is known in advance, and a server that
  refuses to sign is remembered for the session.
- Lightning: node info, channels, opening channels and connecting peers,
  creating and paying invoices, payment history, Lightning addresses.
- Channel peers are shown by name, from a bundled directory of about six
  thousand nodes plus any nickname you give a peer yourself. Both are offline —
  see [Reading Lightning without asking anyone](#reading-lightning-without-asking-anyone).
- Pasting a BOLT11 invoice shows its amount, description and expiry before you
  pay it, decoded on the phone.
- Fee defaults everywhere they are needed: a block target on-chain, an estimated
  sat/vB for a channel open, a 3% routing ceiling on Lightning. The rest of the
  knobs are one tap away, with the collapsed row stating what is in force.
- Pull payments and payouts, including LNURL-withdraw claim codes, approval with
  revision checking, and the automated payout processors.

**Running the server**
- Store settings in full: checkout behaviour, speed policy, network fee mode,
  receipts, payment-method configuration, wallet generation, rates and rate
  scripts, store users and roles, email.
- Webhooks with delivery history and redelivery.
- Server administration: sync status per chain, users, roles, email, internal
  Lightning node.
- Notifications, with background polling and system alerts. Tapping one opens
  the screen it refers to — the invoice, the payouts, the pull payment, the user
  awaiting approval — switching store first when the notification belongs to
  another one. The browser is left for the two links that genuinely live
  elsewhere: a release announcement and an invitation to accept.

**The app itself**
- Multiple servers, switched without re-pairing.
- Material 3 with dynamic colour, an adaptive layout that becomes a navigation
  rail on tablets and unfolded foldables, predictive back, themed launcher icon,
  launcher shortcuts.
- One spring-based motion vocabulary across every screen, with shared elements
  carrying a list row into the screen it opens, skeleton placeholders instead
  of spinners, and full respect for the system's "remove animations" setting.
  See [Motion](#motion).
- Biometric app lock, screenshot blocking, privacy mode that masks amounts.
- Tor support for `.onion` instances through Orbot.

---

## Design decisions

### Motion

Everything that moves reads its timing from one file, `ui/theme/Motion.kt`, and
everything in it is a spring. Not a stylistic preference: a spring carries
velocity, so an animation interrupted halfway — a second tap, a back gesture
abandoned mid-swipe, a section closed while it is still opening — continues
from where it actually was instead of restarting on a fresh curve. That
discontinuity is most of what makes an interface feel dated.

The constants are Material 3's *expressive* motion tokens rather than its
standard ones: softer, slower, and carrying a trace of overshoot on anything
with extent, while opacity stays critically damped so a fade never has to pass
through more-than-opaque to arrive. They are restated in that file rather than
imported, because Material 3 1.4.0 marks `MotionScheme`,
`MaterialExpressiveTheme` and the token class all `internal` — several of them
are public in the compiled class file, which is a trap rather than an opening.
The one honest consequence is recorded in the file: motion *inside* a Material
component, such as a Switch thumb, still runs on Material's stiffer standard
springs, and there is no supported way to change that yet.

Three rules the rest of it follows. Screens slide and stay opaque — they never
fade or zoom, because a screen is a solid surface and the shrinking-rectangle
zoom is the Android 4 activity transition no matter how modern the spring
driving it. A region that swaps its contents scales by four percent as it
cross-fades, so two unrelated layouts are separated in depth rather than
spending the transition as an unreadable double exposure. And a list waiting
for data shows rows in outline where the rows will be, so the data arrives into
a page that is already the right shape instead of one that jumps.

`ANIMATOR_DURATION_SCALE` is honoured throughout, which is what the
accessibility "Remove animations" setting writes to. It suppresses travel, not
feedback: screens still change, balances still update, taps still respond —
nothing crosses the display to do it. Motion sickness is a real reason to stop
using an app, and a payment terminal is not a good place to find that out.

### One Send

A BOLT11 invoice can only be paid over Lightning and an address can only be paid
on-chain. The destination already carries the answer, so the app does not ask the
question first: Send opens one screen with one field, and what lands in that
field decides which form is under it. Paste an invoice and the fee ceiling and
the expiry countdown appear; paste an address and the block targets and RBF do.

The switch above the field is still there, for the sender who wants to choose
before they have anything to paste, and for the one code that is honestly both —
a BIP21 URI carrying an address *and* a `lightning=` invoice. That code used to
lose half of itself depending on which screen read it. Now the address half wins
when there is an on-chain wallet to pay it from, and a line under the field says
the invoice is there too, one tap away.

Each rail keeps its own destination, so switching by hand leaves what you typed
where you typed it. A destination that turns out to belong to the other rail is
the one exception: it moves rather than being copied, because a leftover address
sitting in the invoice field is only something to be found later and not
recognised.

Both halves keep their own view model and their own send path —
`ui/screens/send/` — because they are genuinely different transactions. What is
shared is the field, the rail switch, and the decision between them, which is
`railFor` and is pure enough to test without a device.

### Reading Lightning without asking anyone

Greenfield gives a channel's remote node as a 33-byte public key and nothing
else. There is no endpoint anywhere in the API that will turn that into an
alias — the gossip graph knows, but the only way to ask it from a phone is to
hand the pubkey to a third-party explorer, and telling an explorer which nodes
you peer with maps your node's topology for whoever is watching. Wallets that
attach to the node itself, Zeus among them, simply ask it: `GET
/v1/graph/node/{pubkey}` on LND, `listnodes` on Core Lightning. A Greenfield
client has no such door, so this app carries the answer with it instead.

Names resolve in three steps, in `core/lightning/NodeDirectory.kt`. A nickname
you set on the device always wins. Then a short hand-verified list held in that
file, for the exchange and service nodes where being wrong would matter. Then
`assets/lnnodes.bin`, about six thousand aliases lifted from the public graph.
An unrecognised peer is shown as a shortened key rather than given an invented
name. Nicknames are stored with the rest of the encrypted settings and are never
sent to the server, and no lookup of any kind leaves the phone.

The asset is generated by `scripts/build-node-directory.rb` against a
mempool.space instance, on a developer machine, and the resulting file and its
capture date are both visible in the about screen. Two honest limits. Tor-only
nodes carry no geolocation and are almost entirely missing, which is roughly
half the graph — the half a merchant is least likely to open a channel to, but
missing all the same. And the snapshot ages between releases; the alternative is
a runtime lookup, which is the thing this design exists to avoid.

Aliases are self-declared and not unique, so the generator does not copy the
graph verbatim. It drops any name claimed by more than one node unless one of
them is overwhelmingly the larger, drops anything that folds onto a
hand-verified name from a pubkey that is not the verified one, and folds the
Cyrillic and Greek letters a sans face draws identically to Latin ones, so
"Кraken" cannot pass for "Kraken". Six thousand filtered names are worth having;
six thousand unchecked ones would be worse than none.

The table is sorted by key and memory-mapped straight out of the APK, so a
lookup is a binary search over a file that was never parsed, never decompressed
and never copied onto the heap. That is why the asset is listed under
`androidResources.noCompress`: an entry the packager stored rather than deflated
can be handed to the kernel as an offset and a length.

BOLT11 invoices are decoded the same way, in `core/lightning/Bolt11.kt` —
bech32 and the tagged fields, about two hundred lines and no dependency. Asking
the server what an invoice says would disclose the payee and the amount before
the operator has decided whether to pay, and would leave the preview blank
whenever the node is unreachable.

Two things that decoder deliberately does not do. It does not verify the
signature: nothing here authorises a payment, the node validates the invoice
when it is asked to pay it, and the output only ever reaches a preview card. And
it does not recover the payee key from the signature when the optional `n` field
is absent — that needs a secp256k1 point recovery, and hand-rolling one to get
it subtly wrong would put the *wrong* counterparty's name in front of someone
about to spend money. A blank field is the better failure.

### The dependency budget

The entire third-party surface is AndroidX and JetBrains, plus **one** other
library:

| Area | Used | Deliberately not used |
| --- | --- | --- |
| HTTP | `java.net.HttpURLConnection` | OkHttp, Retrofit, Ktor |
| JSON | `kotlinx.serialization` | Moshi, Gson, Jackson |
| DI | a hand-written `AppGraph` | Dagger, Hilt, Koin, KSP |
| Storage | AES-GCM sealed files + Android Keystore | Room, DataStore, SQLDelight |
| Background work | `JobScheduler` | WorkManager |
| QR decode/encode | `zxing-core` | ML Kit, Google Play services |
| Images | none | Coil, Glide |
| Analytics | none | everything |

On Android, `HttpURLConnection` *is* OkHttp — a fork maintained inside the
platform and patched through system updates. Bundling a second copy would add a
large dependency, a second TLS configuration surface and an interceptor pipeline
this app does not need, in exchange for convenience that
`core/net/Http.kt` provides in about two hundred lines.

The app links against **no Google Play services**, so it runs unmodified on a
de-Googled device and cannot phone home through a transitive SDK.

### Security posture

- **Credentials never exist in the clear on disk.** API keys live in a single
  document sealed with AES-256-GCM under a hardware-backed Android Keystore key
  (StrongBox where available). The key is non-exportable; a root shell plus a
  copy of `filesDir` yields ciphertext.
- **User-installed CAs are not trusted.** A rogue MDM profile or a sideloaded
  root cannot intercept API traffic. Self-hosted instances with a private or
  self-signed certificate are supported through explicit per-account SPKI
  pinning — the user is shown the fingerprint once and accepts it. There is no
  "accept all certificates" toggle, because a switch that disables
  authentication is always eventually left on.
- **TLS only**, with two safe exceptions: `.onion` (Tor already authenticates
  and encrypts, and no CA can issue for an onion name) and loopback.
- **Redirects are not followed across origins**, so an open redirect on the
  server cannot leak the `Authorization` header to a third party.
- **Backups are disabled** and cloud/device-transfer extraction is excluded.
- **`FLAG_SECURE` is on by default**, blocking screenshots and blanking the app
  in the recents switcher.
- **App lock fails closed**: authentication errors never disable the lock.
  Android 11+ requires a successful authenticated Keystore operation; older
  versions use the system device-credential prompt. This protects access to the
  UI; the separate vault key remains usable by background sync and does not
  require user authentication.
- **No push notifications.** Payment alerts come from a poll the app performs
  itself. Push would mean routing payment events through a third party that
  would learn when and how much a merchant is paid.
- **No analytics, no crash reporting, no telemetry.** The `User-Agent` is
  `BTCPayApp/<version>` and carries no device identifiers — not even the build
  type, so a debug build looks identical on the wire. Google's dependency
  metadata block and the VCS stamp are both kept out of the APK.
- Release builds strip logging entirely; nothing logs a key, an address, a
  BOLT11 invoice or a response body in any build.

### Pairing

BTCPay hands an API key back from `/api-keys/authorize` by returning a page that
**auto-submits an HTML form via POST** to the redirect URL. A custom
`myapp://` scheme cannot receive that — Android delivers only the URI to the
intent, so the key is lost, and Chrome separately blocks script-initiated
navigation to external schemes without a user gesture.

So the app opens a **single-use loopback listener** on `127.0.0.1` with an
ephemeral port and a 256-bit nonce in the path, exactly as RFC 8252 prescribes
for native apps, and passes that as the redirect. It receives the full form
body, takes the key, and closes.

Two fallbacks exist: pasting a key created in the BTCPay UI, and email/password
Basic auth used *once* to mint a scoped key. The second often fails by design —
BTCPay disables Basic auth per user by default and rejects it outright when 2FA
is enabled — so the browser flow is the primary path.

---

## Building

Requirements: JDK 17, Android SDK platform 37, build-tools 37.

```sh
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # minified + resource-shrunk
./gradlew :app:testDebugUnitTest # unit tests
./gradlew :app:lintDebug         # lint
```

`local.properties` must point at the SDK:

```properties
sdk.dir=/path/to/Android/sdk
```

Release builds are signed with a 4096-bit RSA key using APK Signature Scheme v2
and v3 only — v1 adds nothing on minSdk 26. Debug builds continue to use
Android's development key.

No signing material is in this repository or beside it. The keystore lives at
`~/.config/btcpayapp/release.jks` (override with `BTCPAYAPP_KEYSTORE`) and its
password comes from the macOS Keychain:

```sh
security find-generic-password -s btcpayapp-release-keystore -w
```

On a machine without a Keychain, set `BTCPAYAPP_KEYSTORE_PASSWORD` instead. If
neither is available the release build is unsigned and says so; it never falls
back to the debug key.

Back the keystore up. It cannot be regenerated, and a signing key that is lost
is an app that can never be updated on a device that already has it.

### Toolchain

| | |
| --- | --- |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.7.1 |
| Kotlin | 2.4.20 (AGP built-in) |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |
| Compose BOM | 2026.09.00 |

AGP 9 provides Kotlin support itself, so `org.jetbrains.kotlin.android` is not
applied — only the Compose and serialization compiler plugins are.

---

## Layout

```
app/src/main/java/com/btcpayapp/
├── AppGraph.kt              the object graph, assembled by hand
├── BtcPayApplication.kt     process lifecycle, notification channels, sync scheduling
├── MainActivity.kt          the single activity: edge-to-edge, splash, FLAG_SECURE
├── core/
│   ├── crypto/              Android Keystore: AES-256-GCM, StrongBox, auth-bound keys
│   ├── net/                 HttpURLConnection engine, TLS policy, SPKI pinning
│   ├── pairing/             loopback API-key receiver, authorize URL builder
│   ├── qr/                  QR encoding
│   ├── scan/                payload classification (BIP21, BOLT11, LNURL, server URLs)
│   ├── security/            app lock and BiometricPrompt
│   ├── store/               transactional encrypted document store
│   ├── sync/                JobScheduler polling, notifications
│   └── util/                money and date formatting, redacting logger
├── data/
│   ├── api/                 client, typed endpoints, DTOs, tolerant serializers
│   ├── model/               accounts, credentials, settings
│   └── session/             active account and store, repositories
└── ui/
    ├── components/          the design system
    ├── nav/                 type-safe routes and the navigation graph
    ├── screens/             one package per area
    └── theme/               colour, type, shape
```

### Conventions

- One `ViewModel` per screen, declared in the same file. Send is the exception,
  and says so: it hosts one per rail, each declared beside the form it drives.
- Screens are pure functions of immutable state plus navigation lambdas; none of
  them touch a `NavController`.
- The API layer throws `ApiException`; view models catch it and put it in state.
  Nothing above the view model ever sees an exception.
- **Money is `BigDecimal` from first parse to final string.** `Double` appears
  nowhere near an amount.

---

## Notes on the Greenfield API

Quirks this client handles, each of which will silently corrupt a naive
implementation:

- Decimals are sent as JSON **strings** (`"5.00"`) — except `paymentTolerance`
  and `feeRate`, which are plain numbers. `blockHeight` and `confirmations` are
  strings too. The serializers accept either form.
- Timestamps are unix **seconds**, not milliseconds — except store invitations,
  which are ISO-8601.
- Time spans use a different unit per field: `invoiceExpiration` is seconds,
  `expirationMinutes` is minutes, `BOLT11Expiration` is days.
- Lightning amounts are **millisatoshi strings**; `LightningOnchainBalance` is
  in satoshi.
- Payment method ids are `BTC-CHAIN` / `BTC-LN` / `BTC-LNURL` since BTCPay 2.0.
  The old `/payment-methods/onchain/{cryptoCode}/wallet` route now answers 410.
- `paymentMethodCriteria` is documented as an object and sent as an array.
  `PullPaymentData.startsAt`/`expiresAt`, `PaymentRequestData.archived` and
  `AppItem.categories`/`taxRate` are sent but undocumented.
- Point-of-Sale and crowdfund apps are **read** with a parsed `items`/`perks`
  array and **written** with a `template`/`perksTemplate` string containing
  serialised JSON.
- Errors come in two shapes: `{code, message}` and a bare **array** of
  `{path, message}`. Status 422 is always the array; 400 may be either, so the
  first byte of the body decides.
- Field casing is inconsistent on purpose: `BOLT11`, `redirectURL`,
  `refundBOLT11Expiration`, `LNURLW`, `UID`.
- Unknown enum values degrade to `Unknown` rather than throwing, so a server
  upgrade cannot break a list screen.

## Licence

MIT — see [LICENSE](LICENSE).
