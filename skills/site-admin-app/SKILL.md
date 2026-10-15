---
name: site-admin-app
description: Build an official admin-only Android alert app for one of my reseller websites (datarjust, phillasty, etrubadatahub, console.ackmorre, …). Scans the site, reports every detail with 10 design-variant images for approval, then builds.
---

# Site admin alert app

Builds the "Maptech Admin"-style Android app for another of the user's PHP reseller websites. The app has:
- instant ringing alerts with a ringtone per alert type
- handling of orders, AFA, complaints, support, shops and withdrawals
- 10 built-in themes and a quick theme switcher
- M-AVETECH credits

The proven reference build is in the user's GitHub repo **MAPTECHGH/admin_alert_app**:
- `template/android-app/`: the Kotlin app with no external dependencies
- `template/server-files/`: `AdminAlertAppController.php`, the routes block and the SQL table
- `skills/site-admin-app/scripts/`: the APK build script and the 10-variant image generator

Clone it (attach the repo first if it isn't attached) and work from it. Don't rebuild from memory.

The work has **two phases with an approval gate between them**. Never start building before the user approves.

---

## Phase 1: Scan and report (no code changes)

### 1. Get the site source
- Use the folder the user names. Their sites live under `C:\Users\mapte\OneDrive\Documents\<SITE NAME>\<domain>`.
- Use the device bridge if it's linked. Otherwise ask for a zip or a GitHub repo.
- Work on a copy. Never touch the live files.

### 2. Read the site end to end
Use `reference/scan-checklist.md`. At minimum, establish:
- **Stack and routing:** the framework or MVC layout, the routes file format and the middleware names. Check whether `[]` middleware skips CSRF and session for JSON endpoints.
- **Auth and roles:** the users table, the password hash column, and how admin roles and permissions work (an equivalent of `Auth::can`, owner or super-admin rules, permission keys).
- **Every entity the app could manage, with its real table and column names:**
  - orders, including network, provider, provider reference, idempotency or send key, api_response and timestamps
  - AFA registrations
  - complaints
  - live support chats
  - contact messages
  - shops and shop withdrawals
  - unclaimed MoMo payments
  - new users
  - anything unique to this site
- **Exact statuses** for each entity, as the admin pages show them, with their counts. List them verbatim. Never invent one: an earlier build wrongly added "Pending".
- **Order processing rules:**
  - providers
  - auto-reprocess and re-send rules, such as the unverified-number rule
  - how "needs review" is decided, for example via `provider_refunded_at` or `provider_balance`
  - when an order's current attempt starts (`accepted_at`), which drives the stuck-order timer
- **Existing admin controller actions** the app can call through the bridge pattern (status, bulk status, refund, approve or reject) and their flash and redirect behaviour.
- **Messaging services** (email and SMS classes and methods). Check that the methods actually exist; on maptechgh some did not.
- **Branding:** the logo file under `public/uploads/logos/`, the site name, and the brand colours from the CSS or logo.
- **Other APIs, bots or tokens.** Leave these untouched, for example the `admin_api` token used by the WhatsApp bot.

### 3. Render the 10 design variants with this site's branding
```bash
cd skills/site-admin-app/scripts/variants
cp <site logo> logo.png
cat > site.json   # site_name, handle, app_name, logo, brand{accent, blue, bg, card} (see site.example.json)
python3 gen.py site.json && python3 shot.py && python3 make_sheet.py
```
- Output: `v01_top.png` to `v10_top.png` plus `variants-1-5.png` and `variants-6-10.png`.
- **Look at both sheets** before sending them. All 10 must look premium; they differ in mood and structure, not quality.
- Variant 10 is the site's own brand theme.

### 4. Present the report and ask for approval
- **Where the report goes:** make it a Docs artifact if that type is listed; otherwise publish a hand-built HTML page.
- **Sending the images:** send both variant sheets with SendUserFile, in the same turn as the report.
- **What the report covers:** use `reference/report-template.md`:
  - the site profile
  - roles and permissions
  - every alert type the app will ring for, with its default on/off state and tone
  - every management screen and action, with the exact statuses
  - the counters on the home screen
  - the server files that will be added or changed
  - the Android package name, app name and icon
  - risks and anything the site is missing, such as an email method that doesn't exist
  - the 10 themes, with the default theme and the 5 quick-switcher picks
- **Then ask with AskUserQuestion:**
  - "Approve and build"
  - "Change something" (they type what)
  - "Pick themes": all 10 by default, 5 starred
- If the user is away, stop here. Phase 2 must not start unapproved.

---

## Phase 2: Build after approval

### Server (PHP)
- Copy `template/server-files/` and adapt `AdminAlertAppController.php` to this site's tables, columns, statuses and permission keys:
  - **Login:** per-device tokens (`X-App-Token: maa_…`), stored as a sha256 hash in `admin_app_devices`.
  - **Permissions:** mirror the site's own `can()` logic. Owner, or no RBAC, means `*`.
  - **Feed:** use per-type cursors on the database clock (`SELECT NOW()`).
  - **Stuck orders:** measure from `GREATEST(created_at, COALESCE(accepted_at, created_at))`. Exclude refunded or needs-review orders, otherwise reprocessed orders falsely ring as "pending over 20 min".
  - **Needs-review alerts:** these are their own type.
  - **Bridge pattern for actions:** run the site's own admin controller methods so the logic and audit trail stay identical:
    - call `session_abort` and set `$_SESSION` and `$_POST`
    - turn redirect plus flash into JSON in a shutdown function
    - call `header_remove` for `Location` and `Set-Cookie`
  - **`dropWebSession()`** on every API call, so polling doesn't create empty session files.
  - **Mask provider names** without `providers.view` permission.
- **routes.php:** append the `/api/admin-app/...` block. **Preserve the file's line endings (CRLF).** Don't touch other routes.
- **Test locally before shipping:**
  - MariaDB plus `php -S` on a copy of the site
  - seed test admins (an owner and a limited staff role)
  - exercise login, permissions, the feed for every alert type, the stuck-order rule, each action and bulk actions
  - confirm no admin session persists

### Android
- Copy `template/android-app/`, then:
  - **Package:** rename it, for example `com.datarjust.adminalerts`, in the directories, `package` lines, `namespace`, `applicationId` and the build `PKG`.
  - **Constants:** set `DEFAULT_SERVER`, `APP_VERSION` and the app name in `strings.xml`.
  - **Logo:** put the site logo in `logo_full.png`, and a padded square version in `ic_launcher_logo.png` for the adaptive icon on white.
  - **Themes:** variant 10 becomes this site's brand theme (`Themes.kt` id 10). Set the default theme and the 5 quick picks the user approved.
  - **Alert types and screens:** keep only what the site supports (`AlertType` access keys, ListActivity modes, drawer items, tiles). Status chips must match the site exactly.
  - **Credits:** keep them as they are. "Developed by M-AVETECH IT SERVICES", www.mavetechservices.com, WhatsApp `https://wa.me/233595054017`.
- **Build:** run `scripts/build_apk.sh`. It refuses to finish if invokedynamic lambdas slipped in, because those crash the app on launch.
  - Create **one keystore per site**, keep it out of git, and reuse it for every update. If the key changes, updates won't install over the old app.
  - Gradle project for the user's own builds: AGP 8.5.2, Kotlin 1.9.24, wrapper 8.7, `keystore.properties` optional.

### Deliver
- **Folders:** put each delivery in a **new folder**, `<SITE> ADMIN APP v1.0.0`, next to the site folder. Never overwrite live files or earlier delivery folders.
- **What goes in it:**
  - the APK
  - `CHANGES.txt`
  - the full source
  - the server files
  - the variant images
  - `INSTALL.txt`: upload the server files, run the SQL, install the APK and sign in with the website admin email
- **Copying to the PC:** `device_commit_files` takes at most 50 files per call, so split into batches. `.github/` paths are protected, so ship the workflow as `android-app/build-apk.yml`.
- **Sending:** send the APK with SendUserFile.
- **Install warning:** tell the user Play Protect will say "App blocked… hasn't seen an app from this developer". They tap **Install anyway**. To remove the warning, file a Play Protect appeal at https://support.google.com/googleplay/android-developer/contact/protectappeals or publish the app privately on Google Play.
- **Testing:** say plainly what was tested locally and that it hasn't been run on a real device.

## Toolchain (one-time, in the cloud workspace)
```bash
apt-get install -y aapt apksigner zipalign dalvik-exchange openjdk-17-jdk-headless mariadb-server php-cli php-mysql
# kotlinc 1.9.24 -> /home/claude/tools/kotlinc ; android-34.jar -> /home/claude/tools/android-34.jar
pip install playwright --break-system-packages   # Chromium is preinstalled at /opt/pw-browsers
```
- **Gradle wrapper:** services.gradle.org is blocked, so generating the wrapper needs `--no-validate-url --offline`.
- **Servers die when idle:** if MariaDB or `php -S` stops, restart with `(mysqld_safe --user=mysql &)` and `php -S` again.
- **Don't `pkill -f "php -S"`:** it kills the shell.

## Lessons from the Maptech build
- **Ask what's wrong before changing designs.** The user will say plainly what's "not nice".
- **Drawer:** full height, about 74% of the screen width (max 292dp). Gradient icon tiles, glowing right edge, About and Sign out as pills at the bottom.
- **Hide switched-off alert types.** Types switched off in Alert settings must not appear as priority alerts, and are not stored.
- **Home screen alerts:** show the latest 5 and a "View all" button that opens the Priority alerts page (grouped by day, filter chips).
- **About:** a dedicated page for the app, the developer and contacts.
