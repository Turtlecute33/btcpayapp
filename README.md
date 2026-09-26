<p align="center">
  <img src=".github/assets/banner.svg" alt="BtcPayServer for Android: an unofficial, native Android client for BTCPay Server" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Turtlecute33/btcpayapp/releases/latest"><b>Download</b></a>
  &nbsp;·&nbsp;
  <a href="#build">Build</a>
  &nbsp;·&nbsp;
  <a href="LICENSE">MIT licence</a>
</p>

> [!IMPORTANT]
> **This is an unofficial app.** It is a community project. The BTCPay Server
> Foundation and the BTCPay Server contributors do not make, endorse or support
> it. For the official project, go to [btcpayserver.org](https://btcpayserver.org).

## Features

- **Terminal**: keypad point of sale, tips, live checkout QR.
- **Invoices**: search, filters, refunds, payment requests, crowdfunds.
- **Wallets**: on-chain and Lightning, one Send screen for both.
- **Payouts**: pull payments, approvals, payout processors.
- **Admin**: stores, users, webhooks, email, server status.
- **Alerts**: payments, payouts and server notifications, checked by the phone.

## Privacy

- The app talks only to your server. No analytics, no crash reports, no push
  service, no Google Play services.
- API keys are encrypted with a hardware-backed Android Keystore key.
- Biometric app lock, screenshot blocking, and a privacy mode that hides amounts.
- Tor `.onion` servers work through Orbot.

## Install

1. Download the APK and its `.sha256` file from
   [Releases](https://github.com/Turtlecute33/btcpayapp/releases/latest).
2. Verify the download:

   ```sh
   shasum -a 256 -c btcpayapp-*.apk.sha256
   apksigner verify --print-certs btcpayapp-*.apk
   ```

   The signing certificate SHA-256 must be
   `67ac728e39bb4866e613c8f676ab4cbd2baaa6a0d2728c53dcf9913f993727ba`.
3. Install the APK, open the app and pair it with your server.

Requires Android 8.0 or later and BTCPay Server 2.0 or later.

## Build

Requires JDK 17 and the Android SDK with platform 37.

```sh
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:testDebugUnitTest   # unit tests
```

## Licence

[MIT](LICENSE). "BTCPay Server" and the BTCPay Server logo belong to their owners.
