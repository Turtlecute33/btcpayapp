# Security

This app holds API keys that can move money. Please report a vulnerability
privately. Do not put its details in a public issue.

## Report a vulnerability

Use GitHub's private reporting:
[Report a vulnerability](https://github.com/Turtlecute33/btcpayapp/security/advisories/new).

If that page does not let you report, open an
[issue](https://github.com/Turtlecute33/btcpayapp/issues/new) that asks for a
private contact. Write no details of the problem in it.

Include the app version, the Android version, the BTCPay Server version, and
the steps to reproduce.

A vulnerability in BTCPay Server itself goes to the
[BTCPay Server project](https://github.com/btcpayserver/btcpayserver/security),
not here.

## Supported versions

Only the latest release gets security fixes. The app has no update check, so
watch the [releases](https://github.com/Turtlecute33/btcpayapp/releases) page.

## Crash traces

Release APKs are minified. The maintainer keeps the R8 mapping file
(`mapping.txt`) of each release, so a stack trace from a release APK can be
read. Send the trace as it is.

## Verify a download

Every release APK is signed with the same key. The SHA-256 of the signing
certificate is:

```
67ac728e39bb4866e613c8f676ab4cbd2baaa6a0d2728c53dcf9913f993727ba
```

```sh
apksigner verify --print-certs btcpayapp-*.apk
```

Do not install an APK signed with a different certificate.
