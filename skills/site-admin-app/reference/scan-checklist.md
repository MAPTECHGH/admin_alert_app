# Site scan checklist (Phase 1)

Write each item down with its file path and line, so the report can point to evidence.

## Structure
- [ ] Framework or MVC layout. Entry point (`public/index.php`), router, base controller, DB helper (PDO wrapper name), env and config files.
- [ ] Routes file format, e.g. `[METHOD, URI, Controller::class, method, [middleware]]`. Middleware names and what an empty `[]` does (CSRF, session, auth).
- [ ] Line endings of routes.php (CRLF or LF) and the PHP version features used.

## Auth and permissions
- [ ] Users table: id, name, email, `password_hash` column and role column.
- [ ] Admin roles: which role values count as admin, and the owner or super-admin rule.
- [ ] RBAC tables, e.g. `admin_roles` and `admin_role_permissions`. List every permission key, e.g. `orders.view`, `orders.manage`, `providers.view`, `afa.*`, `complaints.*`, `support.*`, `shops.*`.
- [ ] The `Auth::can()` equivalent, quoted, so the app mirrors it exactly.

## Entities (table, key columns, statuses verbatim, admin URL)
- [ ] Orders: network, bundle, phone, amount, status, provider, provider_reference, idempotency/send key, api_response(s), created_at, accepted_at, delivered_at, failed_at, provider_refunded_at, provider_balance, source (web/api/shop), user.
- [ ] Order history and audit tables (provider attempts, send keys, audit log).
- [ ] AFA registrations: fields, statuses, refund rule, how "needs review" is decided.
- [ ] Complaints: statuses, reply mechanism (email and SMS service and method — confirm the method exists).
- [ ] Live support: threads, messages, unread flag, close action.
- [ ] Contact messages: read flag.
- [ ] Shops: application statuses (pending, approved, rejected, suspended); shop orders; withdrawals and their statuses.
- [ ] Payments: unclaimed MoMo or claims table.
- [ ] Anything unique to this site, e.g. iShare, result checkers, agents.

## Processing rules
- [ ] Providers list and how one is chosen. Auto-reprocess and re-send rules, e.g. the unverified-number rule, and which columns they update.
- [ ] What makes an order "stuck" and which timestamp starts the current attempt.
- [ ] What makes an order "needs review".

## Admin actions the app can bridge to
- [ ] Order status, bulk status, refund.
- [ ] AFA status, bulk status, refund.
- [ ] Shop approve, reject, suspend, unsuspend; withdrawal approve and reject.
- [ ] For each: controller::method, required POST fields, CSRF check, flash keys, redirect target.

## Branding
- [ ] Site name (settings table or config), logo path, favicon, brand colours (CSS variables or logo colours).

## Leave untouched
- [ ] Existing APIs, bot tokens (`admin_api`), webhooks and cron jobs.
