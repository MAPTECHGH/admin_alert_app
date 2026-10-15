# <SITE NAME> Admin App: site report and build plan

_For approval. Nothing will be built or changed until you approve._

## 1. Site profile
Domain, stack, router and middleware, PHP version, where the source was read from.

## 2. Who can use the app
Admin roles found, the owner rule, and permission keys. What each role will see in the app.

## 3. Alerts that will ring
| Alert | Trigger (exact rule) | Default | Tone | Full-screen |
|---|---|---|---|---|
| New complaint | complaints row with status … | On | Siren | On |
| Order failed | … | On | Alarm | Off |
| Order stuck | processing > 20 min since the current attempt started (accepted_at); excludes needs-review | On | Pulse | Off |
| Needs review | provider_refunded_at / provider_balance … | On | Alarm | Off |
| Order re-sent | … | Off | Chime | Off |
| … | | | | |

## 4. Screens and actions
For each entity: list filters, the **exact status chips with today's counts**, the detail fields, and the actions (single and bulk). Note which site controller method each action calls.

## 5. Home screen counters
Which tiles appear and what each counts.

## 6. Server changes
New files (controller, SQL table), the routes block to append, and existing files touched (should be none besides routes.php). How it will be tested before delivery.

## 7. Android app
App name, package name, icon (site logo), default server URL, version, and signing (new key for this site).

## 8. Themes
All 10 variants are included (images attached). Default theme: …. Quick switcher (5): ….

## 9. Risks and gaps found on the site
E.g. a missing email method, status inconsistencies, missing timestamps, slow queries without indexes.

## 10. What you'll receive
Folder name, its contents, install steps, and the Play Protect "Install anyway" note.
