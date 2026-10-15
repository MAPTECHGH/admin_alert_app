# Admin Alert App

Official admin-only Android app for MAPTECH GLOBAL reseller websites. Developed by **M-AVETECH IT SERVICES** ([mavetechservices.com](https://www.mavetechservices.com)).

- Instant ringing alerts with a ringtone per alert type: complaints, failed, stuck and needs-review orders, AFA, shops, withdrawals, support chats and more.
- Handle orders and AFA from your phone: status changes, bulk actions, refunds, API responses and timelines.
- Reply to complaints and live chats; approve shops and withdrawals.
- 10 built-in themes with a 5-theme quick switcher, a side drawer menu, a Priority alerts page and an About page.

## Repo layout
| Path | What it is |
|---|---|
| `template/android-app/` | Kotlin Android app (no external dependencies; Gradle project + local no-Gradle build). Current: v1.2.3 |
| `template/server-files/` | `AdminAlertAppController.php`, the `admin_app_devices` SQL table and the routes block to append to `config/routes.php` |
| `skills/site-admin-app/` | Claude skill: scan another site, report every detail with 10 design-variant images, and build after approval |
| `maptechgh/design-variants/` | The 10 theme designs rendered for maptechgh.com |

## Using the skill
Save `skills/site-admin-app` as a Claude skill, then say for example: *"Build the admin app for datarjust.com — the files are in Documents\DATARJUST\datarjust.com"*. Claude scans the site and sends a full report plus the 10 variant images. It builds only after you approve.

## Installing the APK
Android may show **"App blocked to protect your device"** (Google Play Protect) because the app isn't from the Play Store. Tap **Install anyway**. To remove the warning, file a Play Protect appeal or publish the app privately on Google Play.

> Signing keys (`*.jks`, `keystore.properties`) are never committed.
