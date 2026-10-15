<?php
declare(strict_types=1);

namespace App\Controllers\Api;

use App\Core\Controller;
use App\Models\Complaint;

/**
 * MAPTECH ADMIN ALERTS — official admin-only Android app API.
 *
 * Separate from the WhatsApp-bot companion API (AdminAppController /
 * admin_api_tokens) on purpose:
 *   • every admin signs in with their OWN website email + password;
 *   • every phone gets its OWN token (admin_app_devices), revocable one
 *     at a time, so signing in on a phone never kills the bot's token;
 *   • every action is checked against the admin's role permissions
 *     (same RBAC rules as Auth::can on the website) and written to the
 *     admin audit log under that admin's name.
 *
 * Auth header on every call except /login:  X-App-Token: maa_xxx
 * Routes are registered with [] middleware (JSON, no CSRF, no session
 * needed) and authenticate themselves through requireDevice().
 */
class AdminAlertAppController extends Controller
{
    private ?array $admin   = null;   // users row of the signed-in admin
    private ?array $device  = null;   // admin_app_devices row
    private ?array $perms   = null;   // ['*'] or list of permission keys
    private bool   $isOwner = false;

    private const APP_MIN_POLL = 10;

    // ════════════════════════════════════════════════════════════
    //  AUTH
    // ════════════════════════════════════════════════════════════

    // POST /api/admin-app/login  {email, password, device_name, app_version}
    public function login(): void
    {
        $this->dropWebSession();
        $this->ensureTables();
        $in       = $this->body();
        $email    = strtolower(trim((string)($in['email'] ?? '')));
        $password = (string)($in['password'] ?? '');
        $device   = mb_substr(trim((string)($in['device_name'] ?? 'Android phone')), 0, 120);
        $version  = mb_substr(trim((string)($in['app_version'] ?? '')), 0, 20);

        if ($email === '' || $password === '') {
            $this->fail('Email and password are required.', 422);
        }

        try {
            $ipTracker = new \App\Services\IpTrackerService();
            if ($ipTracker->isBlocked($ipTracker->getClientIp())) {
                $this->fail('Access denied.', 403);
            }
        } catch (\Throwable) {}

        if (\App\Services\BruteForceGuard::accountLocked('login', $email)) {
            $this->fail('Too many failed attempts. Please try again in 15 minutes.', 429);
        }

        $user = $this->db->fetchOne('SELECT * FROM users WHERE LOWER(email) = ? LIMIT 1', [$email]);

        if (!$user || !password_verify($password, (string)$user['password_hash'])) {
            \App\Services\BruteForceGuard::recordFailure('login', $email);
            $this->fail('Invalid email or password.', 401);
        }
        \App\Services\BruteForceGuard::clear('login');

        if (($user['role'] ?? '') !== 'admin') {
            $this->fail('This app is for administrators only.', 403);
        }
        if (($user['status'] ?? '') !== 'active') {
            $this->fail('Your account is not active.', 403);
        }

        $token = 'maa_' . bin2hex(random_bytes(32));
        $this->db->execute(
            'INSERT INTO admin_app_devices
                (admin_user_id, token_hash, device_name, app_version, status, last_ip, last_seen_at, created_at)
             VALUES (?, ?, ?, ?, "active", ?, NOW(), NOW())',
            [(int)$user['id'], hash('sha256', $token), $device, $version, $this->clientIp()]
        );
        $deviceId = $this->db->lastInsertId();

        $this->admin  = $user;
        $this->device = ['id' => $deviceId];
        $this->loadPermissions();
        $this->audit('admin_app.login', 'admin_app_device', (string)$deviceId, ['device' => $device]);

        $this->json([
            'success'     => true,
            'token'       => $token,
            'admin'       => $this->adminPayload(),
            'server_time' => date('Y-m-d H:i:s'),
        ]);
    }

    // POST /api/admin-app/logout
    public function logout(): void
    {
        $this->requireDevice();
        $this->db->execute(
            'UPDATE admin_app_devices SET status = "revoked", revoked_at = NOW() WHERE id = ?',
            [(int)$this->device['id']]
        );
        $this->audit('admin_app.logout', 'admin_app_device', (string)$this->device['id']);
        $this->json(['success' => true]);
    }

    // GET /api/admin-app/me
    public function me(): void
    {
        $this->requireDevice();
        $this->json(['success' => true, 'admin' => $this->adminPayload(), 'server_time' => date('Y-m-d H:i:s')]);
    }

    // GET /api/admin-app/devices — own devices (Owner sees every admin's)
    public function devices(): void
    {
        $this->requireDevice();
        $sql = 'SELECT d.id, d.admin_user_id, u.name AS admin_name, d.device_name, d.app_version,
                       d.status, d.last_ip, d.last_seen_at, d.created_at
                  FROM admin_app_devices d JOIN users u ON u.id = d.admin_user_id
                 WHERE d.status = "active"';
        $params = [];
        if (!$this->isOwner) {
            $sql     .= ' AND d.admin_user_id = ?';
            $params[] = (int)$this->admin['id'];
        }
        $rows = $this->db->fetchAll($sql . ' ORDER BY d.last_seen_at DESC LIMIT 100', $params);
        foreach ($rows as &$r) {
            $r['id']            = (int)$r['id'];
            $r['admin_user_id'] = (int)$r['admin_user_id'];
            $r['is_this_device'] = $r['id'] === (int)$this->device['id'];
        }
        unset($r);
        $this->json(['success' => true, 'devices' => $rows]);
    }

    // POST /api/admin-app/devices/{id}/revoke
    public function revokeDevice(string $id): void
    {
        $this->requireDevice();
        $row = $this->db->fetchOne('SELECT * FROM admin_app_devices WHERE id = ? LIMIT 1', [(int)$id]);
        if (!$row) $this->fail('Device not found.', 404);
        if (!$this->isOwner && (int)$row['admin_user_id'] !== (int)$this->admin['id']) {
            $this->fail('You can only sign out your own devices.', 403);
        }
        $this->db->execute(
            'UPDATE admin_app_devices SET status = "revoked", revoked_at = NOW() WHERE id = ?', [(int)$id]
        );
        $this->audit('admin_app.device_revoked', 'admin_app_device', $id);
        $this->json(['success' => true]);
    }

    // ════════════════════════════════════════════════════════════
    //  ALERTS
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/summary
    public function summary(): void
    {
        $this->requireDevice();
        $this->json(['success' => true, 'summary' => $this->buildSummary(), 'server_time' => date('Y-m-d H:i:s')]);
    }

    /**
     * GET /api/admin-app/feed
     *   ?c_complaint=&c_support=&c_contact=&c_unclaimed=&c_user=   (last ids seen)
     *   &c_failed=&c_stuck=                                       (last datetimes seen)
     *   &stuck_min=20
     *
     * An empty / missing cursor is initialised to "now" and returns no
     * events for that type — so a freshly signed-in phone never rings
     * for hundreds of old items. The app stores the returned cursors
     * and sends them back on the next poll.
     */
    public function feed(): void
    {
        $this->requireDevice();

        $limit    = 15;
        $stuckMin = max(5, min(240, (int)($_GET['stuck_min'] ?? 20)));
        // Use the DATABASE clock (rows are stamped with MySQL NOW()), so a
        // PHP/MySQL timezone difference can never make the app miss events.
        $nowTs    = strtotime((string)$this->db->fetchColumn('SELECT NOW()')) ?: time();
        $upper    = date('Y-m-d H:i:s', $nowTs - 2);          // small safety lag for in-flight writes
        $stuckTo  = date('Y-m-d H:i:s', $nowTs - $stuckMin * 60);
        $events   = [];
        $cursors  = [];

        // ── New complaints (web + shop) ───────────────────────────
        if ($this->can('complaints.manage')) {
            $c = $this->idCursor('c_complaint', 'SELECT COALESCE(MAX(id),0) FROM complaints');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT c.id, c.message, c.source, c.created_at, c.contact_name,
                            u.name AS user_name, o.reference, o.recipient
                       FROM complaints c
                       LEFT JOIN users  u ON u.id = c.user_id
                       LEFT JOIN orders o ON o.id = c.order_id
                      WHERE c.id > ? ORDER BY c.id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $who = $r['contact_name'] ?: ($r['user_name'] ?: 'A customer');
                    $events[] = $this->event('complaint', (int)$r['id'], 'critical',
                        'New complaint' . ($r['source'] === 'shop' ? ' (shop)' : '') . ' — ' . $who,
                        'Order ' . ($r['reference'] ?? '?') . ' • ' . ($r['recipient'] ?? '') . "\n" . $this->clip($r['message']),
                        $r['created_at']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_complaint'] = $c['value'];
        }

        // ── New client messages in Live Support ───────────────────
        if ($this->can('support.view') || $this->can('support.manage')) {
            $c = $this->idCursor('c_support', "SELECT COALESCE(MAX(id),0) FROM support_messages WHERE sender_type = 'client'");
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT m.id, m.session_id, m.message, m.attachment_path, m.created_at, u.name AS user_name
                       FROM support_messages m
                       JOIN support_sessions s ON s.id = m.session_id
                       LEFT JOIN users u ON u.id = s.user_id
                      WHERE m.id > ? AND m.sender_type = 'client'
                      ORDER BY m.id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $msg = trim((string)$r['message']);
                    if ($msg === '' && !empty($r['attachment_path'])) $msg = '📷 Sent an image';
                    $events[] = $this->event('support', (int)$r['session_id'], 'high',
                        'Support: ' . ($r['user_name'] ?: 'Client'), $this->clip($msg), $r['created_at'],
                        (int)$r['id']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_support'] = $c['value'];
        }

        // ── Contact form messages ─────────────────────────────────
        if ($this->can('contact_messages.manage')) {
            $c = $this->idCursor('c_contact', 'SELECT COALESCE(MAX(id),0) FROM contact_messages');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT id, name, subject, message, created_at FROM contact_messages
                      WHERE id > ? ORDER BY id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $events[] = $this->event('contact', (int)$r['id'], 'normal',
                        'Contact message — ' . $r['name'],
                        ($r['subject'] ? $r['subject'] . ': ' : '') . $this->clip($r['message']),
                        $r['created_at']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_contact'] = $c['value'];
        }

        $canOrders  = $this->can('orders.view') || $this->can('orders.manage');
        $hasRefCols = $canOrders && \App\Helpers\ProviderRefundHandler::ensureColumns($this->db);

        // ── Failed orders (data + AFA) ────────────────────────────
        if ($canOrders) {
            $c = $this->timeCursor('c_failed', $upper, $nowTs);
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT o.id, o.reference, o.recipient, o.price, o.capacity_gb, o.failed_at, o.api_response,
                            u.name AS user_name, n.name AS network_name
                       FROM orders o
                       LEFT JOIN users u ON u.id = o.user_id
                       LEFT JOIN networks n ON n.id = o.network_id
                      WHERE o.status = 'failed' AND o.failed_at > ? AND o.failed_at <= ?
                      ORDER BY o.failed_at ASC LIMIT {$limit}",
                    [$c['value'], $upper]
                );
                $last = $c['value'];
                foreach ($rows as $r) {
                    $reason = $this->apiMessage($r['api_response']);
                    $events[] = $this->event('failed_order', (int)$r['id'], 'high',
                        'Order failed — ' . ($r['network_name'] ?? '') . ' ' . rtrim(rtrim((string)$r['capacity_gb'], '0'), '.') . 'GB',
                        ($r['user_name'] ?? 'Guest') . ' • ' . $r['recipient'] . ' • GH₵' . number_format((float)$r['price'], 2)
                            . ($reason ? "\n" . $this->clip($reason) : ''),
                        $r['failed_at']);
                    $last = $r['failed_at'];
                }
                $c['value'] = count($rows) >= $limit ? $last : $upper;
            }
            $cursors['c_failed'] = $c['value'];

            // ── Orders stuck pending longer than stuck_min ────────
            // v1.1 FIX: measured from the CURRENT attempt. accepted_at is reset
            // on every auto re-send (unverified-number reprocess) and every
            // admin requeue, so time spent with an earlier provider no longer
            // counts. Orders parked under ⚠️ Needs Review are left out here —
            // they get their own "needs_review" alert.
            $since = 'GREATEST(o.created_at, COALESCE(o.accepted_at, o.created_at))';
            $c = $this->timeCursor('c_stuck', $stuckTo, $nowTs);
            if ($c['fresh'] === false && $c['value'] < $stuckTo) {
                $rows = $this->db->fetchAll(
                    "SELECT o.id, o.reference, o.recipient, o.status, {$since} AS pending_since,
                            u.name AS user_name, n.name AS network_name
                       FROM orders o
                       LEFT JOIN users u ON u.id = o.user_id
                       LEFT JOIN networks n ON n.id = o.network_id
                      WHERE o.status IN ('accepted','placed','processing')
                        AND {$since} > ? AND {$since} <= ?
                        " . ($hasRefCols ? 'AND o.provider_refunded_at IS NULL' : '') . "
                        AND COALESCE(o.api_response, '') NOT LIKE '%\"needs_review\":\"provider_balance\"%'
                      ORDER BY pending_since ASC LIMIT {$limit}",
                    [$c['value'], $stuckTo]
                );
                $last = $c['value'];
                foreach ($rows as $r) {
                    $events[] = $this->event('stuck_order', (int)$r['id'], 'high',
                        "Order pending over {$stuckMin} min — " . ($r['network_name'] ?? ''),
                        ($r['user_name'] ?? 'Guest') . ' • ' . $r['recipient'] . ' • ' . $r['status']
                            . ' since ' . substr((string)$r['pending_since'], 11, 5) . ' • ' . $r['reference'],
                        $r['pending_since'], $r['id'] . '@' . $r['pending_since']);
                    $last = $r['pending_since'];
                }
                $c['value'] = count($rows) >= $limit ? $last : $stuckTo;
            }
            $cursors['c_stuck'] = $c['value'];

            // ── Orders that went to ⚠️ Needs Review ───────────────
            // (provider refunded / refused an unverified number and the
            //  setting is "review", or OUR provider balance was too low)
            $c = $this->timeCursor('c_review', $upper, $nowTs);
            if ($c['fresh'] === false) {
                $conds  = [];
                $params = [];
                if ($hasRefCols) {
                    $conds[] = '(o.provider_refunded_at > ? AND o.provider_refunded_at <= ?
                                 AND (o.processing_at IS NULL OR o.provider_refunded_at >= o.processing_at))';
                    array_push($params, $c['value'], $upper);
                }
                $conds[] = "(COALESCE(o.api_response, '') LIKE '%\"needs_review\":\"provider_balance\"%'
                             AND o.updated_at > ? AND o.updated_at <= ?)";
                array_push($params, $c['value'], $upper);
                $rows = $this->db->fetchAll(
                    "SELECT o.id, o.reference, o.recipient, o.admin_note, o.api_response, o.updated_at,
                            " . ($hasRefCols ? 'o.provider_refunded_at' : 'NULL') . " AS flagged_at,
                            u.name AS user_name, n.name AS network_name, p.name AS provider_name
                       FROM orders o
                       LEFT JOIN users u ON u.id = o.user_id
                       LEFT JOIN networks n ON n.id = o.network_id
                       LEFT JOIN providers p ON p.id = o.provider_id
                      WHERE o.status = 'processing' AND (" . implode(' OR ', $conds) . ")
                      ORDER BY o.updated_at ASC LIMIT 30",
                    $params
                );
                foreach ($rows as $r) {
                    $balance = str_contains((string)$r['api_response'], '"needs_review":"provider_balance"');
                    $reason  = $balance ? 'Provider balance too low — top up, then requeue.' : $this->lastNoteLine($r['admin_note'], 'Needs review');
                    $events[] = $this->event('needs_review', (int)$r['id'], 'high',
                        'Order needs review — ' . ($r['network_name'] ?? '') . ' → ' . $r['recipient'],
                        ($r['user_name'] ?? 'Guest') . ' • ' . $r['reference'] . "\n" . $this->clip($reason),
                        $r['flagged_at'] ?: $r['updated_at'],
                        $r['id'] . '@' . ($balance ? 'bal' : (string)$r['flagged_at']));
                }
                $c['value'] = $upper;
            }
            $cursors['c_review'] = $c['value'];

            // ── Orders auto re-sent to another provider ───────────
            if ($hasRefCols) {
                $c = $this->timeCursor('c_resent', $upper, $nowTs);
                if ($c['fresh'] === false) {
                    $rows = $this->db->fetchAll(
                        "SELECT o.id, o.reference, o.recipient, o.auto_reprocessed_from, o.auto_reprocessed_at,
                                o.admin_note, u.name AS user_name, n.name AS network_name, p.name AS provider_name
                           FROM orders o
                           LEFT JOIN users u ON u.id = o.user_id
                           LEFT JOIN networks n ON n.id = o.network_id
                           LEFT JOIN providers p ON p.id = o.provider_id
                          WHERE o.auto_reprocessed_at > ? AND o.auto_reprocessed_at <= ?
                          ORDER BY o.auto_reprocessed_at ASC LIMIT {$limit}",
                        [$c['value'], $upper]
                    );
                    $last = $c['value'];
                    foreach ($rows as $r) {
                        $events[] = $this->event('reprocessed', (int)$r['id'], 'normal',
                            'Order re-sent: ' . ($r['auto_reprocessed_from'] ?: '?') . ' → ' . ($this->providerLabel($r['provider_name']) ?: '?'),
                            ($r['network_name'] ?? '') . ' • ' . $r['recipient'] . ' • ' . ($r['user_name'] ?? 'Guest')
                                . "\n" . $this->clip($this->lastNoteLine($r['admin_note'], 'Auto-reprocessed')),
                            $r['auto_reprocessed_at'], $r['id'] . '@' . $r['auto_reprocessed_at']);
                        $last = $r['auto_reprocessed_at'];
                    }
                    $c['value'] = count($rows) >= $limit ? $last : $upper;
                }
                $cursors['c_resent'] = $c['value'];
            }
        }

        // ── AFA orders ────────────────────────────────────────────
        if ($this->can('afa.view') || $this->can('afa.manage')) {
            $c = $this->idCursor('c_afa', 'SELECT COALESCE(MAX(id),0) FROM afa_orders');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT a.id, a.full_name, a.reference, a.price, a.source, a.created_at, u.name AS user_name
                       FROM afa_orders a LEFT JOIN users u ON u.id = a.user_id
                      WHERE a.id > ? ORDER BY a.id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $events[] = $this->event('afa_new', (int)$r['id'], 'normal',
                        'New AFA registration — ' . $r['full_name'],
                        ($r['user_name'] ?? 'Guest') . ' • GH₵' . number_format((float)$r['price'], 2) . ' • ' . $r['source'] . ' • ' . $r['reference'],
                        $r['created_at']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_afa'] = $c['value'];

            $c = $this->timeCursor('c_afa_failed', $upper, $nowTs);
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT a.id, a.full_name, a.reference, a.failed_at, a.api_response, u.name AS user_name
                       FROM afa_orders a LEFT JOIN users u ON u.id = a.user_id
                      WHERE a.status = 'failed' AND a.failed_at > ? AND a.failed_at <= ?
                      ORDER BY a.failed_at ASC LIMIT {$limit}",
                    [$c['value'], $upper]
                );
                $last = $c['value'];
                foreach ($rows as $r) {
                    $msg = $this->apiMessage($r['api_response']);
                    $events[] = $this->event('afa_failed', (int)$r['id'], 'high',
                        'AFA registration failed — ' . $r['full_name'],
                        ($r['user_name'] ?? 'Guest') . ' • ' . $r['reference'] . ($msg ? "\n" . $this->clip($msg) : ''),
                        $r['failed_at']);
                    $last = $r['failed_at'];
                }
                $c['value'] = count($rows) >= $limit ? $last : $upper;
            }
            $cursors['c_afa_failed'] = $c['value'];

            if (\App\Helpers\AfaReview::ensureColumns($this->db)) {
                $c = $this->timeCursor('c_afa_review', $upper, $nowTs);
                if ($c['fresh'] === false) {
                    $rows = $this->db->fetchAll(
                        "SELECT a.id, a.full_name, a.reference, a.needs_review_at, a.review_reason
                           FROM afa_orders a
                          WHERE " . \App\Helpers\AfaReview::WHERE . "
                            AND a.needs_review_at > ? AND a.needs_review_at <= ?
                          ORDER BY a.needs_review_at ASC LIMIT {$limit}",
                        [$c['value'], $upper]
                    );
                    foreach ($rows as $r) {
                        $events[] = $this->event('afa_review', (int)$r['id'], 'high',
                            'AFA needs review — ' . $r['full_name'],
                            $r['reference'] . "\n" . $this->clip((string)$r['review_reason']),
                            $r['needs_review_at'], 'afa' . $r['id'] . '@' . $r['needs_review_at']);
                    }
                    $c['value'] = $upper;
                }
                $cursors['c_afa_review'] = $c['value'];
            }
        }

        // ── Shops: new applications & withdrawal requests ─────────
        if ($this->can('shops.manage')) {
            $c = $this->idCursor('c_shop', 'SELECT COALESCE(MAX(id),0) FROM shops');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT s.id, s.name, s.slug, s.status, s.created_at, u.name AS owner_name
                       FROM shops s JOIN users u ON u.id = s.user_id
                      WHERE s.id > ? ORDER BY s.id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    if ($r['status'] === 'pending') {
                        $events[] = $this->event('shop_application', (int)$r['id'], 'normal',
                            'New shop application — ' . $r['name'],
                            'By ' . $r['owner_name'] . ' • /shop/' . $r['slug'] . ' • waiting for approval', $r['created_at']);
                    }
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_shop'] = $c['value'];

            $c = $this->idCursor('c_withdraw', 'SELECT COALESCE(MAX(id),0) FROM shop_withdrawals');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT w.id, w.amount, w.type, w.momo_number, w.momo_name, w.requested_at, s.name AS shop_name
                       FROM shop_withdrawals w JOIN shops s ON s.id = w.shop_id
                      WHERE w.id > ? ORDER BY w.id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $events[] = $this->event('shop_withdrawal', (int)$r['id'], 'normal',
                        'Withdrawal request GH₵' . number_format((float)$r['amount'], 2) . ' — ' . $r['shop_name'],
                        ($r['type'] === 'wallet_transfer' ? 'To wallet' : 'MoMo ' . ($r['momo_number'] ?? '') . ' ' . ($r['momo_name'] ?? '')),
                        $r['requested_at']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_withdraw'] = $c['value'];
        }

        // ── Unclaimed MoMo payments ───────────────────────────────
        if ($this->can('send_claim.view') || $this->can('send_claim.manage')) {
            $c = $this->idCursor('c_unclaimed', 'SELECT COALESCE(MAX(id),0) FROM unclaimed_payments');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT id, amount, sender, reference_used, momo_transaction_id, created_at
                       FROM unclaimed_payments WHERE id > ? ORDER BY id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $events[] = $this->event('unclaimed_payment', (int)$r['id'], 'normal',
                        'Unclaimed payment GH₵' . number_format((float)$r['amount'], 2),
                        'From ' . ($r['sender'] ?: 'unknown') . ' • Ref "' . ($r['reference_used'] ?: '-') . '" • TxID ' . $r['momo_transaction_id'],
                        $r['created_at']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_unclaimed'] = $c['value'];
        }

        // ── New registrations ─────────────────────────────────────
        if ($this->can('users.view')) {
            $c = $this->idCursor('c_user', 'SELECT COALESCE(MAX(id),0) FROM users');
            if ($c['fresh'] === false) {
                $rows = $this->db->fetchAll(
                    "SELECT id, name, phone, created_at FROM users
                      WHERE id > ? AND role = 'client' ORDER BY id ASC LIMIT {$limit}",
                    [$c['value']]
                );
                foreach ($rows as $r) {
                    $events[] = $this->event('new_user', (int)$r['id'], 'low',
                        'New registration — ' . $r['name'], (string)($r['phone'] ?? ''), $r['created_at']);
                    $c['value'] = (int)$r['id'];
                }
            }
            $cursors['c_user'] = $c['value'];
        }

        $this->json([
            'success'      => true,
            'events'       => $events,
            'cursors'      => $cursors,
            'summary'      => $this->buildSummary(),
            'server_time'  => date('Y-m-d H:i:s'),
            'min_poll_sec' => self::APP_MIN_POLL,
        ]);
    }

    // ════════════════════════════════════════════════════════════
    //  COMPLAINTS
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/complaints?status=&q=&page=
    public function complaints(): void
    {
        $this->requireDevice();
        $this->requirePerm('complaints.manage');

        $status = trim((string)($_GET['status'] ?? ''));
        if (!in_array($status, ['', 'open', 'reviewing', 'resolved', 'rejected'], true)) $status = '';
        $search = trim((string)($_GET['q'] ?? ''));
        [$page, $limit, $offset] = $this->pageParams();

        $model = new Complaint();
        $total = $model->countAll($status, $search);
        $rows  = $model->allWithRelations($status, $search, $limit, $offset);

        $this->json([
            'success'    => true,
            'complaints' => array_map([$this, 'formatComplaint'], $rows),
            'counts'     => $model->countByStatus(),
            'pagination' => $this->pagination($total, $page, $limit),
        ]);
    }

    // GET /api/admin-app/complaints/{id}
    public function complaintShow(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('complaints.manage');

        $r = $this->db->fetchOne(
            "SELECT c.*, u.name AS user_name, u.email AS user_email, u.phone AS user_phone,
                    o.reference AS order_reference, o.recipient AS order_recipient, o.price AS order_price,
                    o.status AS order_status, o.capacity_gb, o.api_response, o.admin_note, o.created_at AS order_created_at,
                    n.name AS network_name, b.label AS bundle_label
               FROM complaints c
               LEFT JOIN users    u ON u.id = c.user_id
               LEFT JOIN orders   o ON o.id = c.order_id
               LEFT JOIN networks n ON n.id = o.network_id
               LEFT JOIN bundles  b ON b.id = o.bundle_id
              WHERE c.id = ? LIMIT 1",
            [(int)$id]
        );
        if (!$r) $this->fail('Complaint not found.', 404);

        $out = $this->formatComplaint($r);
        $out['user_phone']           = $r['contact_phone'] ?: ($r['user_phone'] ?? null);
        $out['order_status']         = $r['order_status'];
        $out['order_created_at']     = $r['order_created_at'];
        $out['capacity_gb']          = $r['capacity_gb'] !== null ? (float)$r['capacity_gb'] : null;
        $out['provider_message']     = $this->apiMessage($r['api_response']);
        $out['admin_note']           = $r['admin_note'];
        $this->json(['success' => true, 'complaint' => $out]);
    }

    // POST /api/admin-app/complaints/{id}/reply  {status, reply, send_email, send_sms}
    public function complaintReply(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('complaints.manage');

        $complaintId = (int)$id;
        $complaint   = (new Complaint())->find($complaintId);
        if (!$complaint) $this->fail('Complaint not found.', 404);

        $in        = $this->body();
        $newStatus = trim((string)($in['status'] ?? 'reviewing'));
        $reply     = trim((string)($in['reply'] ?? ''));
        $sendEmail = !empty($in['send_email']);
        $sendSms   = !empty($in['send_sms']);

        if (!in_array($newStatus, ['open', 'reviewing', 'resolved', 'rejected'], true)) $newStatus = 'reviewing';
        if ($reply === '') $this->fail('Reply cannot be empty.', 422);

        $this->db->execute(
            'UPDATE complaints
                SET status = ?, admin_reply = ?, reply_sent_email = ?, reply_sent_sms = ?,
                    replied_at = NOW(), updated_at = NOW()
              WHERE id = ?',
            [$newStatus, $reply, $sendEmail ? 1 : 0, $sendSms ? 1 : 0, $complaintId]
        );

        // NOTE: the website's ComplaintAdminController calls
        // NotificationService::sendComplaintReply()/sendComplaintReplySms(),
        // which do not exist in NotificationService (the error is swallowed,
        // so nothing is sent). The app sends directly with the same
        // EmailService / SmsService the rest of the site uses.
        $emailSent = false;
        $smsSent   = false;
        $settings  = \App\Models\Setting::getAll();
        $siteName  = (string)($settings['site_name'] ?? (defined('APP_NAME') ? APP_NAME : 'Maptech Global'));
        $reference = (string)($this->db->fetchColumn('SELECT reference FROM orders WHERE id = ? LIMIT 1', [(int)$complaint['order_id']]) ?: '');

        if ($sendEmail) {
            $toEmail = $complaint['contact_email']
                ?: $this->db->fetchColumn('SELECT email FROM users WHERE id = ? LIMIT 1', [(int)$complaint['user_id']]);
            if ($toEmail) {
                try {
                    $name = $complaint['contact_name']
                        ?: (string)$this->db->fetchColumn('SELECT name FROM users WHERE id = ? LIMIT 1', [(int)$complaint['user_id']]);
                    $body = '<div style="font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',sans-serif;color:#374151">'
                          . '<h2 style="margin:0 0 12px">Update on your complaint</h2>'
                          . '<p>Hi ' . htmlspecialchars((string)$name) . ',</p>'
                          . ($reference !== '' ? '<p>Order: <strong>' . htmlspecialchars($reference) . '</strong></p>' : '')
                          . '<p>' . nl2br(htmlspecialchars($reply)) . '</p>'
                          . '<p>Status: <strong>' . ucfirst($newStatus) . '</strong></p>'
                          . '<p style="color:#9ca3af;font-size:.8rem">— ' . htmlspecialchars($siteName) . ' Support</p></div>';
                    (new \App\Services\EmailService($settings))->send(
                        (string)$toEmail, 'Update on your complaint — ' . $siteName, $body, (string)$name
                    );
                    $emailSent = true;
                } catch (\Throwable $e) {
                    error_log('[AdminAlertApp complaint email] ' . $e->getMessage());
                }
            }
        }
        if ($sendSms) {
            $toPhone = $complaint['contact_phone']
                ?: $this->db->fetchColumn('SELECT phone FROM users WHERE id = ? LIMIT 1', [(int)$complaint['user_id']]);
            if ($toPhone) {
                try {
                    $text = $siteName . ': ' . ($reference !== '' ? "Complaint on order {$reference} — " : '')
                          . ucfirst($newStatus) . '. ' . $reply;
                    $smsSent = (new \App\Services\SmsService($settings))->sendHubtel(mb_substr($text, 0, 459), (string)$toPhone);
                } catch (\Throwable $e) {
                    error_log('[AdminAlertApp complaint sms] ' . $e->getMessage());
                }
            }
        }

        $this->audit('complaint.reply', 'complaint', (string)$complaintId,
            ['status' => $newStatus, 'email' => $sendEmail, 'sms' => $sendSms, 'via' => 'admin_app']);

        $this->json([
            'success'    => true,
            'status'     => $newStatus,
            'email_sent' => $emailSent,
            'sms_sent'   => $smsSent,
            'replied_at' => date('Y-m-d H:i:s'),
        ]);
    }

    // ════════════════════════════════════════════════════════════
    //  LIVE SUPPORT
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/support?tab=open|closed&q=&page=
    public function support(): void
    {
        $this->requireDevice();
        if (!$this->can('support.view') && !$this->can('support.manage')) $this->fail('No permission for Live Support.', 403);

        $tab    = ($_GET['tab'] ?? 'open') === 'closed' ? 'closed' : 'open';
        $search = trim((string)($_GET['q'] ?? ''));
        [$page, $limit, $offset] = $this->pageParams();

        $where  = 's.status = ?';
        $params = [$tab];
        if ($search !== '') {
            $where   .= ' AND (u.name LIKE ? OR s.subject LIKE ?)';
            $like     = "%{$search}%";
            $params[] = $like;
            $params[] = $like;
        }

        $total = (int)$this->db->fetchColumn(
            "SELECT COUNT(*) FROM support_sessions s JOIN users u ON u.id = s.user_id WHERE {$where}", $params
        );
        $rows = $this->db->fetchAll(
            "SELECT s.id, s.subject, s.status, s.last_message_at, s.last_message_by, s.unread_by_admin,
                    s.created_at, u.name AS client_name, a.name AS attended_by_name,
                    (SELECT m.message FROM support_messages m WHERE m.session_id = s.id ORDER BY m.id DESC LIMIT 1) AS last_message
               FROM support_sessions s
               JOIN users u ON u.id = s.user_id
               LEFT JOIN users a ON a.id = s.attended_by
              WHERE {$where}
              ORDER BY s.last_message_at DESC
              LIMIT ? OFFSET ?",
            [...$params, $limit, $offset]
        );
        foreach ($rows as &$r) {
            $r['id']              = (int)$r['id'];
            $r['unread_by_admin'] = (bool)$r['unread_by_admin'];
            $r['last_message']    = $this->clip((string)$r['last_message'], 120);
        }
        unset($r);

        $this->json(['success' => true, 'sessions' => $rows, 'pagination' => $this->pagination($total, $page, $limit)]);
    }

    // GET /api/admin-app/support/{id}
    public function supportShow(string $id): void
    {
        $this->requireDevice();
        if (!$this->can('support.view') && !$this->can('support.manage')) $this->fail('No permission for Live Support.', 403);

        $sid     = (int)$id;
        $session = $this->db->fetchOne(
            "SELECT s.*, u.name AS client_name, u.email AS client_email, u.phone AS client_phone, a.name AS attended_by_name
               FROM support_sessions s
               JOIN users u ON u.id = s.user_id
               LEFT JOIN users a ON a.id = s.attended_by
              WHERE s.id = ? LIMIT 1",
            [$sid]
        );
        if (!$session) $this->fail('Session not found.', 404);

        if (!$this->can('users.view_full_pii')) {
            $session['client_email'] = \App\Core\Mask::email((string)$session['client_email']);
            $session['client_phone'] = \App\Core\Mask::phone((string)$session['client_phone']);
        }

        $messages = $this->db->fetchAll(
            'SELECT m.id, m.sender_type, m.message, m.attachment_path, m.created_at, u.name AS sender_name
               FROM support_messages m
               LEFT JOIN users u ON u.id = m.sender_id
              WHERE m.session_id = ? ORDER BY m.id ASC',
            [$sid]
        );
        foreach ($messages as &$m) {
            $m['id']             = (int)$m['id'];
            $m['attachment_url'] = !empty($m['attachment_path'])
                ? \App\Services\ImageUploadService::url('support', $m['attachment_path'])
                : null;
            unset($m['attachment_path']);
        }
        unset($m);

        $this->db->execute('UPDATE support_sessions SET unread_by_admin = 0 WHERE id = ?', [$sid]);

        $this->json([
            'success'    => true,
            'session'    => [
                'id'               => (int)$session['id'],
                'subject'          => $session['subject'],
                'status'           => $session['status'],
                'client_name'      => $session['client_name'],
                'client_email'     => $session['client_email'],
                'client_phone'     => $session['client_phone'],
                'attended_by_name' => $session['attended_by_name'],
                'created_at'       => $session['created_at'],
            ],
            'messages'   => $messages,
            'can_manage' => $this->can('support.manage'),
        ]);
    }

    // POST /api/admin-app/support/{id}/reply  {message}
    public function supportReply(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('support.manage');

        $sid = (int)$id;
        $exists = $this->db->fetchColumn('SELECT COUNT(*) FROM support_sessions WHERE id = ?', [$sid]);
        if (!$exists) $this->fail('Session not found.', 404);

        $message = trim((string)($this->body()['message'] ?? ''));
        if ($message === '') $this->fail('Reply cannot be empty.', 422);
        if (mb_strlen($message) > 4000) $message = mb_substr($message, 0, 4000);

        $this->db->execute(
            'INSERT INTO support_messages (session_id, sender_type, sender_id, message, attachment_path, help_link_id, email_sent, created_at)
             VALUES (?, "admin", ?, ?, NULL, NULL, 0, NOW())',
            [$sid, (int)$this->admin['id'], $message]
        );
        $this->db->execute(
            'UPDATE support_sessions
                SET last_message_at = NOW(), last_message_by = "admin", unread_by_client = 1, updated_at = NOW()
              WHERE id = ?',
            [$sid]
        );
        $this->audit('support.reply', 'support_session', (string)$sid, ['via' => 'admin_app']);
        $this->json(['success' => true]);
    }

    // POST /api/admin-app/support/{id}/close
    public function supportClose(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('support.manage');
        $this->db->execute(
            "UPDATE support_sessions SET status = 'closed', closed_at = NOW(), attended_by = ?, updated_at = NOW()
              WHERE id = ? AND status = 'open'",
            [(int)$this->admin['id'], (int)$id]
        );
        $this->audit('support.close', 'support_session', $id, ['via' => 'admin_app']);
        $this->json(['success' => true]);
    }

    // ════════════════════════════════════════════════════════════
    //  CONTACT MESSAGES
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/contact?status=&page=
    public function contact(): void
    {
        $this->requireDevice();
        $this->requirePerm('contact_messages.manage');

        $status = trim((string)($_GET['status'] ?? ''));
        if (!in_array($status, ['', 'new', 'read', 'replied'], true)) $status = '';
        [$page, $limit, $offset] = $this->pageParams();

        $where  = $status !== '' ? 'status = ?' : '1=1';
        $params = $status !== '' ? [$status] : [];

        $total = (int)$this->db->fetchColumn("SELECT COUNT(*) FROM contact_messages WHERE {$where}", $params);
        $rows  = $this->db->fetchAll(
            "SELECT id, name, email, subject, message, status, admin_reply, replied_at, created_at
               FROM contact_messages WHERE {$where} ORDER BY id DESC LIMIT ? OFFSET ?",
            [...$params, $limit, $offset]
        );
        foreach ($rows as &$r) $r['id'] = (int)$r['id'];
        unset($r);

        $this->json(['success' => true, 'messages' => $rows, 'pagination' => $this->pagination($total, $page, $limit)]);
    }

    // POST /api/admin-app/contact/{id}/read
    public function contactRead(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('contact_messages.manage');
        $this->db->execute("UPDATE contact_messages SET status = 'read' WHERE id = ? AND status = 'new'", [(int)$id]);
        $this->json(['success' => true]);
    }

    // ════════════════════════════════════════════════════════════
    //  ORDERS
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/orders
    //   ?status=  accepted|placed|processing|delivered|failed|refunded|cancelled|pending|needs_review
    //   &date_filter= all|today|yesterday|last7|custom  &date_from=YYYY-MM-DD &date_to=YYYY-MM-DD
    //   &network= id  &source=  &q=  &page=  &limit=
    // Same filters as Admin → Orders on the website.
    public function orders(): void
    {
        $this->requireDevice();
        if (!$this->can('orders.view') && !$this->can('orders.manage')) $this->fail('No permission for orders.', 403);

        $status = trim((string)($_GET['status'] ?? ''));
        $search = trim((string)($_GET['q'] ?? ''));
        [$page, $limit, $offset] = $this->pageParams(25, 100);

        [$dateWhere, $dateParams] = $this->dateFilter('o.created_at');
        $baseWhere  = '1=1' . $dateWhere;
        $baseParams = $dateParams;

        $network = (int)($_GET['network'] ?? 0);
        if ($network > 0) { $baseWhere .= ' AND o.network_id = ?'; $baseParams[] = $network; }
        $source = trim((string)($_GET['source'] ?? ''));
        if ($source !== '' && preg_match('/^[a-z_]+$/', $source)) { $baseWhere .= ' AND o.source = ?'; $baseParams[] = $source; }

        // Search: client send key (MT_…/MTB_…), reference, number, customer, provider ref
        $keyIds = [];
        if ($search !== '' && preg_match('/^MTB?_[A-Za-z0-9_]+$/i', $search)) {
            try { $keyIds = \App\Helpers\OrderSendKey::orderIdsForKey($this->db, $search); } catch (\Throwable) {}
        }
        if ($keyIds) {
            $baseWhere .= ' AND o.id IN (' . implode(',', array_map('intval', $keyIds)) . ')';
        } elseif ($search !== '') {
            $like = "%{$search}%";
            if ($this->can('users.view_full_pii')) {
                $baseWhere .= ' AND (o.reference LIKE ? OR o.recipient LIKE ? OR u.name LIKE ? OR u.email LIKE ? OR o.provider_reference = ? OR o.idempotency_key = ?)';
                array_push($baseParams, $like, $like, $like, $like, $search, $search);
            } else {
                $c = ['o.reference LIKE ?', 'u.name LIKE ?', 'o.provider_reference = ?', 'o.idempotency_key = ?'];
                array_push($baseParams, $like, $like, $search, $search);
                if (preg_match('/^0[0-9]{9}$/', $search)) { $c[] = 'o.recipient = ?'; $baseParams[] = $search; }
                if (filter_var($search, FILTER_VALIDATE_EMAIL)) { $c[] = 'u.email = ?'; $baseParams[] = $search; }
                $baseWhere .= ' AND (' . implode(' OR ', $c) . ')';
            }
        }

        $where  = $baseWhere;
        $params = $baseParams;
        if ($status === 'needs_review') {
            $where .= ' AND ' . $this->needsReviewSql();
        } elseif ($status === 'pending') {
            $where .= " AND o.status IN ('accepted','placed','processing')";
        } elseif (in_array($status, ['accepted', 'placed', 'processing', 'delivered', 'failed', 'refunded', 'cancelled'], true)) {
            $where   .= ' AND o.status = ?';
            $params[] = $status;
        }

        $from = 'FROM orders o LEFT JOIN users u ON u.id = o.user_id';
        $total = (int)$this->db->fetchColumn("SELECT COUNT(*) {$from} WHERE {$where}", $params);

        // Counts per status for the current date / network / search (for the filter chips)
        $counts = ['all' => 0];
        foreach ($this->db->fetchAll("SELECT o.status, COUNT(*) AS c {$from} WHERE {$baseWhere} GROUP BY o.status", $baseParams) as $r) {
            $counts[$r['status']] = (int)$r['c'];
            $counts['all'] += (int)$r['c'];
        }
        $counts['needs_review'] = (int)$this->db->fetchColumn("SELECT COUNT(*) {$from} WHERE {$baseWhere} AND " . $this->needsReviewSql(), $baseParams);

        $sort = $status === 'delivered' ? 'o.delivered_at' : 'o.created_at';
        $rows = $this->db->fetchAll(
            "SELECT o.*, u.name AS user_name, u.email AS user_email, n.name AS network_name,
                    b.label AS bundle_label, p.name AS provider_name
               FROM orders o
               LEFT JOIN users u ON u.id = o.user_id
               LEFT JOIN networks n ON n.id = o.network_id
               LEFT JOIN bundles  b ON b.id = o.bundle_id
               LEFT JOIN providers p ON p.id = o.provider_id
              WHERE {$where}
              ORDER BY {$sort} DESC, o.id DESC
              LIMIT ? OFFSET ?",
            [...$params, $limit, $offset]
        );

        $this->json([
            'success'    => true,
            'orders'     => array_map(fn($o) => $this->formatOrder($o, false), $rows),
            'counts'     => $counts,
            'networks'   => $page === 1 ? $this->db->fetchAll('SELECT id, name FROM networks ORDER BY sort_order ASC, id ASC') : [],
            'statuses'   => ['accepted', 'placed', 'processing', 'delivered', 'failed', 'refunded', 'cancelled'],
            'can_manage' => $this->can('orders.manage'),
            'providers'  => ($page === 1 && $this->can('providers.manage'))
                ? $this->db->fetchAll("SELECT id, name FROM providers WHERE status = 'active' ORDER BY name ASC") : [],
            'pagination' => $this->pagination($total, $page, $limit),
        ]);
    }

    // GET /api/admin-app/orders/{id} — full details, API responses and timeline
    public function orderShow(string $id): void
    {
        $this->requireDevice();
        if (!$this->can('orders.view') && !$this->can('orders.manage')) $this->fail('No permission for orders.', 403);
        \App\Helpers\ProviderRefundHandler::ensureColumns($this->db);

        $o = $this->db->fetchOne(
            "SELECT o.*, u.name AS user_name, u.email AS user_email, u.phone AS user_phone,
                    n.name AS network_name, b.label AS bundle_label, p.name AS provider_name
               FROM orders o
               LEFT JOIN users u ON u.id = o.user_id
               LEFT JOIN networks n ON n.id = o.network_id
               LEFT JOIN bundles  b ON b.id = o.bundle_id
               LEFT JOIN providers p ON p.id = o.provider_id
              WHERE o.id = ? LIMIT 1",
            [(int)$id]
        );
        if (!$o) $this->fail('Order not found.', 404);

        $out = $this->formatOrder($o, true);

        // Send key the order was last sent to the provider under
        try { $out['send_key'] = \App\Helpers\OrderSendKey::last($this->db, $o); } catch (\Throwable) { $out['send_key'] = null; }

        // Provider route & re-send history (ADH Group → Databundleshub → …)
        $hist = [];
        try { $hist = \App\Helpers\ProviderRefundHandler::history($o); } catch (\Throwable) {}
        $out['provider_history'] = array_map(fn($h) => [
            'at'       => $h['at'] ?? null,
            'provider' => $this->providerLabel($h['provider'] ?? null),
            'to'       => $this->providerLabel($h['to'] ?? null),
            'ref'      => $h['ref'] ?? null,
            'reason'   => $h['reason'] ?? null,
            'outcome'  => $h['outcome'] ?? null,
            'by'       => $h['by'] ?? null,
            'response' => isset($h['response']) ? $this->pretty($h['response']) : null,
        ], $hist);

        // Full audit log (every status change + every replaced API response)
        $audit = [];
        try {
            \App\Helpers\OrderAudit::install($this->db);
            foreach (\App\Helpers\OrderAudit::forOrder($this->db, (int)$id) as $r) {
                $details = json_decode((string)($r['details'] ?? ''), true);
                $audit[] = [
                    'id'                 => (int)$r['id'],
                    'event'              => $r['event'],
                    'old_status'         => $r['old_status'],
                    'new_status'         => $r['new_status'],
                    'old_provider'       => $this->providerLabel($r['old_provider'] ?? null),
                    'new_provider'       => $this->providerLabel($r['new_provider'] ?? null),
                    'provider_reference' => $r['provider_reference'],
                    'api_response'       => !empty($r['api_response']) ? $this->pretty($r['api_response']) : null,
                    'api_message'        => !empty($r['api_response']) ? $this->apiMessage((string)$r['api_response']) : null,
                    'why'                => is_array($details) ? ($details['why'] ?? null) : null,
                    'created_at'         => $r['created_at'],
                ];
            }
        } catch (\Throwable) {}
        $out['audit'] = $audit;

        // Status timestamps of the current attempt
        $out['timeline'] = [];
        foreach (['created' => 'created_at', 'accepted' => 'accepted_at', 'placed' => 'placed_at', 'processing' => 'processing_at',
                  'delivered' => 'delivered_at', 'failed' => 'failed_at', 'refunded' => 'refunded_at', 'cancelled' => 'cancelled_at',
                  're-sent' => 'auto_reprocessed_at', 'needs review' => 'provider_refunded_at'] as $label => $col) {
            if (!empty($o[$col])) $out['timeline'][] = ['label' => $label, 'at' => $o[$col]];
        }
        usort($out['timeline'], fn($a, $b) => strcmp((string)$a['at'], (string)$b['at']));

        $out['complaint'] = $this->db->fetchOne(
            'SELECT id, status, message, admin_reply, created_at FROM complaints WHERE order_id = ? ORDER BY id DESC LIMIT 1',
            [(int)$id]
        );
        $out['can_manage']           = $this->can('orders.manage');
        $out['can_change_provider']  = $this->can('providers.manage');
        $out['providers']            = $this->can('providers.manage')
            ? $this->db->fetchAll("SELECT id, name FROM providers WHERE status = 'active' ORDER BY name ASC") : [];
        $out['statuses']             = ['accepted', 'placed', 'processing', 'delivered', 'failed', 'refunded', 'cancelled'];
        $this->json(['success' => true, 'order' => $out]);
    }

    // POST /api/admin-app/orders/{id}/refund  {reason}
    public function orderRefund(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('orders.manage');

        $order = $this->db->fetchOne('SELECT * FROM orders WHERE id = ? LIMIT 1', [(int)$id]);
        if (!$order) $this->fail('Order not found.', 404);

        $reason = trim((string)($this->body()['reason'] ?? '')) ?: 'Refunded from admin app';
        $result = (new \App\Services\OrderRefundService())->refund($order);

        $line = sprintf(
            '[Admin App %s — %s] %s. Reason: %s',
            date('Y-m-d H:i'),
            $this->admin['name'],
            $result['ok']
                ? 'Refunded via ' . ($result['method'] ?? 'wallet') . ' (GH₵' . number_format((float)($result['amount'] ?? 0), 2) . ')'
                : 'Refund FAILED: ' . ($result['error'] ?? 'unknown error'),
            $reason
        );
        $note = trim((string)($order['admin_note'] ?? ''));
        $this->db->execute(
            'UPDATE orders SET admin_note = ?, updated_at = NOW() WHERE id = ?',
            [$note !== '' ? $note . "\n" . $line : $line, (int)$id]
        );

        $this->audit('order.refund', 'order', $id, ['via' => 'admin_app', 'reason' => $reason],
            $result['ok'] ? 'success' : 'failed');

        if (!$result['ok']) $this->fail($result['error'] ?? 'Refund failed.', 422);
        $this->json(['success' => true, 'message' => $result['message'] ?? 'Refunded.', 'method' => $result['method'] ?? null]);
    }

    // POST /api/admin-app/orders/{id}/status  {status, provider_id?, key_mode?: same|new}
    // Runs the website's own OrderAdminController::status() — same requeue,
    // claw-back, refund, referral and shop-commission logic.
    public function orderStatus(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('orders.manage');
        $in = $this->body();
        $this->bridge(\App\Controllers\Admin\OrderAdminController::class, 'status', [(string)(int)$id], [
            'status'      => (string)($in['status'] ?? ''),
            'provider_id' => (string)($in['provider_id'] ?? ''),
            'key_mode'    => ($in['key_mode'] ?? 'new') === 'same' ? 'same' : 'new',
        ], 'order.status', 'order', (string)(int)$id);
    }

    // POST /api/admin-app/orders/bulk-status  {ids:[..], status, provider_id?, key_mode?}
    public function ordersBulkStatus(): void
    {
        $this->requireDevice();
        $this->requirePerm('orders.manage');
        $in  = $this->body();
        $ids = $this->idList($in['ids'] ?? []);
        if (!$ids) $this->fail('Select at least one order.', 422);
        $this->bridge(\App\Controllers\Admin\OrderAdminController::class, 'bulkStatus', [], [
            'order_ids'   => $ids,
            'bulk_status' => (string)($in['status'] ?? ''),
            'provider_id' => (string)($in['provider_id'] ?? ''),
            'key_mode'    => ($in['key_mode'] ?? 'new') === 'same' ? 'same' : 'new',
        ], 'order.bulk_status', 'order', implode(',', $ids));
    }

    // ════════════════════════════════════════════════════════════
    //  AFA ORDERS
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/afa?status=&date_filter=&date_from=&date_to=&q=&page=
    public function afa(): void
    {
        $this->requireDevice();
        if (!$this->can('afa.view') && !$this->can('afa.manage')) $this->fail('No permission for AFA orders.', 403);
        $reviewReady = \App\Helpers\AfaReview::ensureColumns($this->db);

        $status = trim((string)($_GET['status'] ?? ''));
        $search = trim((string)($_GET['q'] ?? ''));
        [$page, $limit, $offset] = $this->pageParams(25, 100);

        [$dateWhere, $dateParams] = $this->dateFilter('a.created_at');
        $baseWhere  = '1=1' . $dateWhere;
        $baseParams = $dateParams;
        if ($search !== '') {
            $like = "%{$search}%";
            if ($this->can('users.view_full_pii')) {
                $baseWhere .= ' AND (a.full_name LIKE ? OR a.phone LIKE ? OR a.reference LIKE ? OR a.ghana_card LIKE ? OR u.name LIKE ?)';
                array_push($baseParams, $like, $like, $like, $like, $like);
            } else {
                $c = ['a.full_name LIKE ?', 'a.reference LIKE ?', 'u.name LIKE ?'];
                array_push($baseParams, $like, $like, $like);
                if (preg_match('/^0[0-9]{9}$/', $search)) { $c[] = 'a.phone = ?'; $baseParams[] = $search; }
                if (preg_match('/^GHA-\d+-\d$/i', $search)) { $c[] = 'a.ghana_card = ?'; $baseParams[] = $search; }
                $baseWhere .= ' AND (' . implode(' OR ', $c) . ')';
            }
        }
        $where  = $baseWhere;
        $params = $baseParams;
        if ($status === 'needs_review') {
            $where .= $reviewReady ? ' AND ' . \App\Helpers\AfaReview::WHERE : ' AND 1=0';
        } elseif ($status === 'pending') {
            $where .= " AND a.status IN ('accepted','placed','processing')";
        } elseif (in_array($status, ['accepted', 'placed', 'processing', 'delivered', 'failed', 'refunded'], true)) {
            $where .= ' AND a.status = ?';
            $params[] = $status;
        }

        $from  = 'FROM afa_orders a LEFT JOIN users u ON u.id = a.user_id';
        $total = (int)$this->db->fetchColumn("SELECT COUNT(*) {$from} WHERE {$where}", $params);
        $counts = ['all' => 0];
        foreach ($this->db->fetchAll("SELECT a.status, COUNT(*) AS c {$from} WHERE {$baseWhere} GROUP BY a.status", $baseParams) as $r) {
            $counts[$r['status']] = (int)$r['c'];
            $counts['all'] += (int)$r['c'];
        }
        $counts['needs_review'] = $reviewReady
            ? (int)$this->db->fetchColumn("SELECT COUNT(*) {$from} WHERE {$baseWhere} AND " . \App\Helpers\AfaReview::WHERE, $baseParams) : 0;

        $rows = $this->db->fetchAll(
            "SELECT a.*, u.name AS user_name, u.email AS user_email, p.name AS provider_name
               {$from} LEFT JOIN providers p ON p.id = a.provider_id
              WHERE {$where} ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
            [...$params, $limit, $offset]
        );
        $this->json([
            'success'    => true,
            'orders'     => array_map(fn($a) => $this->formatAfa($a, false), $rows),
            'counts'     => $counts,
            'statuses'   => ['accepted', 'placed', 'processing', 'delivered', 'failed'],
            'can_manage' => $this->can('afa.manage'),
            'pagination' => $this->pagination($total, $page, $limit),
        ]);
    }

    // GET /api/admin-app/afa/{id}
    public function afaShow(string $id): void
    {
        $this->requireDevice();
        if (!$this->can('afa.view') && !$this->can('afa.manage')) $this->fail('No permission for AFA orders.', 403);
        \App\Helpers\AfaReview::ensureColumns($this->db);
        $a = $this->db->fetchOne(
            "SELECT a.*, u.name AS user_name, u.email AS user_email, p.name AS provider_name
               FROM afa_orders a LEFT JOIN users u ON u.id = a.user_id
               LEFT JOIN providers p ON p.id = a.provider_id WHERE a.id = ? LIMIT 1",
            [(int)$id]
        );
        if (!$a) $this->fail('AFA order not found.', 404);
        $out = $this->formatAfa($a, true);
        $out['timeline'] = [];
        foreach (['created' => 'created_at', 'accepted' => 'accepted_at', 'placed' => 'placed_at', 'processing' => 'processing_at',
                  'delivered' => 'delivered_at', 'failed' => 'failed_at', 'refunded' => 'refunded_at', 'needs review' => 'needs_review_at'] as $label => $col) {
            if (!empty($a[$col])) $out['timeline'][] = ['label' => $label, 'at' => $a[$col]];
        }
        usort($out['timeline'], fn($x, $y) => strcmp((string)$x['at'], (string)$y['at']));
        $out['can_manage'] = $this->can('afa.manage');
        $out['statuses']   = ['accepted', 'placed', 'processing', 'delivered', 'failed'];
        $this->json(['success' => true, 'order' => $out]);
    }

    // POST /api/admin-app/afa/{id}/status {status}
    public function afaStatus(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('afa.manage');
        $this->bridge(\App\Controllers\Admin\AfaAdminController::class, 'status', [(string)(int)$id],
            ['status' => (string)($this->body()['status'] ?? '')], 'afa.status', 'afa_order', (string)(int)$id);
    }

    // POST /api/admin-app/afa/bulk-status {ids:[..], status}
    public function afaBulkStatus(): void
    {
        $this->requireDevice();
        $this->requirePerm('afa.manage');
        $in  = $this->body();
        $ids = $this->idList($in['ids'] ?? []);
        if (!$ids) $this->fail('Select at least one AFA order.', 422);
        $this->bridge(\App\Controllers\Admin\AfaAdminController::class, 'bulkStatus', [],
            ['order_ids' => $ids, 'bulk_status' => (string)($in['status'] ?? '')], 'afa.bulk_status', 'afa_order', implode(',', $ids));
    }

    // POST /api/admin-app/afa/{id}/refund
    public function afaRefund(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('afa.manage');
        $this->bridge(\App\Controllers\Admin\AfaAdminController::class, 'refund', [(string)(int)$id], [],
            'afa.refund', 'afa_order', (string)(int)$id);
    }

    // ════════════════════════════════════════════════════════════
    //  SHOPS
    // ════════════════════════════════════════════════════════════

    // GET /api/admin-app/shops?status=pending|active|suspended|rejected|all&q=
    public function shops(): void
    {
        $this->requireDevice();
        $this->requirePerm('shops.manage');
        $status = trim((string)($_GET['status'] ?? 'all'));
        if (!in_array($status, ['all', 'pending', 'active', 'suspended', 'rejected'], true)) $status = 'all';
        $search = trim((string)($_GET['q'] ?? ''));
        [$page, $limit, $offset] = $this->pageParams(30, 100);

        $where = '1=1'; $params = [];
        if ($status !== 'all') { $where .= ' AND s.status = ?'; $params[] = $status; }
        if ($search !== '') {
            $like = "%{$search}%";
            $where .= ' AND (s.name LIKE ? OR s.slug LIKE ? OR u.name LIKE ? OR u.email LIKE ?)';
            array_push($params, $like, $like, $like, $like);
        }
        $total = (int)$this->db->fetchColumn("SELECT COUNT(*) FROM shops s JOIN users u ON u.id = s.user_id WHERE {$where}", $params);
        $rows  = $this->db->fetchAll(
            "SELECT s.id, s.name, s.slug, s.status, s.phone, s.commission_balance, s.commission_earned,
                    s.applied_at, s.approved_at, s.rejected_reason, s.created_at,
                    u.name AS owner_name, u.email AS owner_email,
                    (SELECT COUNT(*) FROM shop_orders so WHERE so.shop_id = s.id) AS order_count,
                    (SELECT COUNT(*) FROM shop_orders so WHERE so.shop_id = s.id AND so.created_at >= CURDATE()) AS orders_today
               FROM shops s JOIN users u ON u.id = s.user_id
              WHERE {$where}
              ORDER BY (s.status = 'pending') DESC, COALESCE(s.applied_at, s.created_at) DESC
              LIMIT ? OFFSET ?",
            [...$params, $limit, $offset]
        );
        foreach ($rows as &$r) {
            $r['id'] = (int)$r['id'];
            $r['order_count'] = (int)$r['order_count'];
            $r['orders_today'] = (int)$r['orders_today'];
            $r['commission_balance'] = (float)$r['commission_balance'];
            $r['commission_earned'] = (float)$r['commission_earned'];
            if (!$this->can('users.view_full_pii')) $r['owner_email'] = \App\Core\Mask::email((string)$r['owner_email']);
        }
        unset($r);
        $counts = ['all' => 0];
        foreach ($this->db->fetchAll('SELECT status, COUNT(*) AS c FROM shops GROUP BY status') as $c) {
            $counts[$c['status']] = (int)$c['c']; $counts['all'] += (int)$c['c'];
        }
        $counts['withdrawals_pending'] = (int)$this->db->fetchColumn("SELECT COUNT(*) FROM shop_withdrawals WHERE status = 'pending'");
        $this->json([
            'success' => true, 'shops' => $rows, 'counts' => $counts,
            'site_url' => defined('APP_URL') ? APP_URL : '',
            'pagination' => $this->pagination($total, $page, $limit),
        ]);
    }

    // POST /api/admin-app/shops/{id}/{action}  action = approve|reject|suspend|unsuspend  {reason?}
    public function shopAction(string $id, string $action): void
    {
        $this->requireDevice();
        $this->requirePerm('shops.manage');
        if (!in_array($action, ['approve', 'reject', 'suspend', 'unsuspend'], true)) $this->fail('Unknown action.', 422);
        $this->bridge(\App\Controllers\Admin\AdminShopController::class, $action, [(string)(int)$id],
            ['reason' => (string)($this->body()['reason'] ?? '')], 'shop.' . $action, 'shop', (string)(int)$id);
    }

    // GET /api/admin-app/shops/{id}/orders?filter=all|pending|processing|delivered|failed&page=
    public function shopOrders(string $id): void
    {
        $this->requireDevice();
        $this->requirePerm('shops.manage');
        $shopId = (int)$id;
        $shop = $this->db->fetchOne(
            'SELECT s.id, s.name, s.slug, s.status, s.commission_balance, u.name AS owner_name
               FROM shops s JOIN users u ON u.id = s.user_id WHERE s.id = ? LIMIT 1', [$shopId]);
        if (!$shop) $this->fail('Shop not found.', 404);
        $filter = trim((string)($_GET['filter'] ?? 'all'));
        if (!in_array($filter, ['all', 'pending', 'processing', 'delivered', 'failed'], true)) $filter = 'all';
        [$page, $limit, $offset] = $this->pageParams(30, 100);
        [$dateWhere, $dateParams] = $this->dateFilter('so.created_at');
        $where = 'so.shop_id = ?' . $dateWhere; $params = [$shopId, ...$dateParams];
        $baseWhere = $where; $baseParams = $params;
        if ($filter !== 'all') { $where .= ' AND so.status = ?'; $params[] = $filter; }
        $total = (int)$this->db->fetchColumn("SELECT COUNT(*) FROM shop_orders so WHERE {$where}", $params);
        $rows = $this->db->fetchAll(
            "SELECT so.id, so.order_id, so.afa_order_id, so.type, so.customer_name, so.customer_phone, so.recipient,
                    so.capacity_gb, so.customer_price, so.owner_cost, so.commission, so.payment_status, so.payment_reference,
                    so.status, so.created_at, n.name AS network_name, o.status AS order_status
               FROM shop_orders so
               LEFT JOIN networks n ON n.id = so.network_id
               LEFT JOIN orders o ON o.id = so.order_id
              WHERE {$where} ORDER BY so.created_at DESC, so.id DESC LIMIT ? OFFSET ?",
            [...$params, $limit, $offset]
        );
        $pii = $this->can('users.view_full_pii');
        foreach ($rows as &$r) {
            foreach (['id', 'order_id', 'afa_order_id'] as $k) $r[$k] = $r[$k] !== null ? (int)$r[$k] : null;
            foreach (['customer_price', 'owner_cost', 'commission', 'capacity_gb'] as $k) $r[$k] = $r[$k] !== null ? (float)$r[$k] : null;
            if (!$pii) { $r['customer_phone'] = \App\Core\Mask::phone((string)$r['customer_phone']); $r['recipient'] = \App\Core\Mask::phone((string)$r['recipient']); }
        }
        unset($r);
        $counts = ['all' => 0];
        foreach ($this->db->fetchAll("SELECT so.status, COUNT(*) AS c FROM shop_orders so WHERE {$baseWhere} GROUP BY so.status", $baseParams) as $c) {
            $counts[$c['status']] = (int)$c['c']; $counts['all'] += (int)$c['c'];
        }
        $totals = $this->db->fetchOne(
            "SELECT COALESCE(SUM(CASE WHEN payment_status='paid' THEN customer_price END),0) AS revenue,
                    COALESCE(SUM(CASE WHEN payment_status='paid' THEN commission END),0) AS commission
               FROM shop_orders so WHERE {$baseWhere}", $baseParams);
        $this->json([
            'success' => true, 'shop' => $shop, 'orders' => $rows, 'counts' => $counts,
            'totals' => ['revenue' => (float)$totals['revenue'], 'commission' => (float)$totals['commission']],
            'pagination' => $this->pagination($total, $page, $limit),
        ]);
    }

    // GET /api/admin-app/shop-withdrawals?status=pending|paid|rejected
    public function shopWithdrawals(): void
    {
        $this->requireDevice();
        $this->requirePerm('shops.manage');
        $status = trim((string)($_GET['status'] ?? 'pending'));
        if (!in_array($status, ['pending', 'paid', 'rejected'], true)) $status = 'pending';
        [$page, $limit, $offset] = $this->pageParams(30, 100);
        $total = (int)$this->db->fetchColumn('SELECT COUNT(*) FROM shop_withdrawals WHERE status = ?', [$status]);
        $rows = $this->db->fetchAll(
            "SELECT w.id, w.amount, w.fee_amount, w.net_amount, w.type, w.status, w.momo_network, w.momo_number, w.momo_name,
                    w.payment_note, w.requested_at, w.actioned_at, s.id AS shop_id, s.name AS shop_name, s.commission_balance,
                    u.name AS owner_name
               FROM shop_withdrawals w JOIN shops s ON s.id = w.shop_id JOIN users u ON u.id = s.user_id
              WHERE w.status = ? ORDER BY w.requested_at DESC LIMIT ? OFFSET ?",
            [$status, $limit, $offset]
        );
        foreach ($rows as &$r) {
            $r['id'] = (int)$r['id']; $r['shop_id'] = (int)$r['shop_id'];
            foreach (['amount', 'fee_amount', 'net_amount', 'commission_balance'] as $k) $r[$k] = $r[$k] !== null ? (float)$r[$k] : null;
        }
        unset($r);
        $counts = [];
        foreach (['pending', 'paid', 'rejected'] as $st) {
            $counts[$st] = (int)$this->db->fetchColumn('SELECT COUNT(*) FROM shop_withdrawals WHERE status = ?', [$st]);
        }
        $this->json(['success' => true, 'withdrawals' => $rows, 'counts' => $counts, 'pagination' => $this->pagination($total, $page, $limit)]);
    }

    // POST /api/admin-app/shop-withdrawals/{id}/{action}  action = approve|reject  {note?}
    public function shopWithdrawalAction(string $id, string $action): void
    {
        $this->requireDevice();
        $this->requirePerm('shops.manage');
        if (!in_array($action, ['approve', 'reject'], true)) $this->fail('Unknown action.', 422);
        $this->bridge(\App\Controllers\Admin\AdminShopController::class, $action === 'approve' ? 'approveWithdrawal' : 'rejectWithdrawal',
            [(string)(int)$id], ['payment_note' => (string)($this->body()['note'] ?? '')], 'shop.withdrawal_' . $action, 'shop_withdrawal', (string)(int)$id);
    }

    // ════════════════════════════════════════════════════════════
    //  INTERNALS
    // ════════════════════════════════════════════════════════════

    private function requireDevice(): void
    {
        $this->dropWebSession();
        $token = trim((string)($_SERVER['HTTP_X_APP_TOKEN'] ?? ''));
        if ($token === '' || !str_starts_with($token, 'maa_')) {
            $this->fail('Please sign in.', 401);
        }

        try {
            $row = $this->db->fetchOne(
                'SELECT d.id AS device_id, d.last_seen_at AS device_last_seen, u.*
                   FROM admin_app_devices d
                   JOIN users u ON u.id = d.admin_user_id
                  WHERE d.token_hash = ? AND d.status = "active"
                  LIMIT 1',
                [hash('sha256', $token)]
            );
        } catch (\Throwable) {
            $row = null; // table not created yet
        }

        if (!$row || ($row['role'] ?? '') !== 'admin' || ($row['status'] ?? '') !== 'active') {
            $this->fail('Session expired or revoked. Please sign in again.', 401);
        }

        try {
            $ipTracker = new \App\Services\IpTrackerService();
            if ($ipTracker->isBlocked($ipTracker->getClientIp())) {
                $this->fail('Access denied.', 403);
            }
        } catch (\Throwable) {}

        $this->device = ['id' => (int)$row['device_id']];
        unset($row['device_id'], $row['device_last_seen'], $row['password_hash'], $row['remember_token']);
        $this->admin = $row;
        $this->loadPermissions();

        $this->db->execute(
            'UPDATE admin_app_devices SET last_seen_at = NOW(), last_ip = ? WHERE id = ?',
            [$this->clientIp(), $this->device['id']]
        );
    }

    /** Same rules as Auth::permissions(): owner / no-role / no-RBAC = everything. */
    private function loadPermissions(): void
    {
        $this->perms   = ['*'];
        $this->isOwner = true;
        try {
            $role = $this->db->fetchOne(
                'SELECT r.* FROM users u JOIN admin_roles r ON r.id = u.admin_role_id WHERE u.id = ? LIMIT 1',
                [(int)$this->admin['id']]
            );
        } catch (\Throwable) {
            return;
        }
        if (!$role) return;

        $this->admin['role_name'] = $role['name'];
        if (($role['slug'] ?? '') === 'owner') return;

        $this->isOwner = false;
        try {
            $rows = $this->db->fetchAll(
                'SELECT permission_key FROM admin_role_permissions WHERE role_id = ?', [(int)$role['id']]
            );
            $this->perms = array_column($rows, 'permission_key');
        } catch (\Throwable) {
            $this->perms = ['*'];
        }
    }

    private function can(string $perm): bool
    {
        return in_array('*', $this->perms ?? [], true) || in_array($perm, $this->perms ?? [], true);
    }

    private function requirePerm(string $perm): void
    {
        if (!$this->can($perm)) $this->fail("Your role doesn't allow this ({$perm}).", 403);
    }

    private function adminPayload(): array
    {
        $can = fn(string ...$keys) => array_reduce($keys, fn($c, $k) => $c || $this->can($k), false);
        return [
            'id'        => (int)$this->admin['id'],
            'name'      => $this->admin['name'],
            'email'     => $this->admin['email'],
            'role_name' => $this->admin['role_name'] ?? ($this->isOwner ? 'Owner' : 'Admin'),
            'is_owner'  => $this->isOwner,
            'site_name' => \App\Models\Setting::get('site_name', defined('APP_NAME') ? APP_NAME : 'Maptech Global'),
            'site_url'  => defined('APP_URL') ? APP_URL : '',
            'access'    => [
                'complaints' => $can('complaints.manage'),
                'support'    => $can('support.view', 'support.manage'),
                'support_reply' => $can('support.manage'),
                'contact'    => $can('contact_messages.manage'),
                'orders'     => $can('orders.view', 'orders.manage'),
                'orders_manage' => $can('orders.manage'),
                'payments'   => $can('send_claim.view', 'send_claim.manage'),
                'users'      => $can('users.view'),
                'afa'        => $can('afa.view', 'afa.manage'),
                'afa_manage' => $can('afa.manage'),
                'shops'      => $can('shops.manage'),
                'providers_manage' => $can('providers.manage'),
            ],
        ];
    }

    private function buildSummary(): array
    {
        $out = [];
        $q   = fn(string $sql) => (int)$this->db->fetchColumn($sql);
        if ($this->can('complaints.manage')) {
            $out['complaints_open'] = $q("SELECT COUNT(*) FROM complaints WHERE status IN ('open','reviewing')");
        }
        if ($this->can('support.view') || $this->can('support.manage')) {
            $out['support_open']   = $q("SELECT COUNT(*) FROM support_sessions WHERE status = 'open'");
            $out['support_unread'] = $q("SELECT COUNT(*) FROM support_sessions WHERE status = 'open' AND unread_by_admin = 1");
        }
        if ($this->can('contact_messages.manage')) {
            $out['contact_new'] = $q("SELECT COUNT(*) FROM contact_messages WHERE status = 'new'");
        }
        if ($this->can('orders.view') || $this->can('orders.manage')) {
            $out['orders_today']        = $q('SELECT COUNT(*) FROM orders WHERE created_at >= CURDATE()');
            $out['orders_processing']   = $q("SELECT COUNT(*) FROM orders WHERE status = 'processing'");
            $out['orders_failed_today'] = $q("SELECT COUNT(*) FROM orders WHERE status = 'failed' AND failed_at >= CURDATE()");
        }
        if ($this->can('orders.view') || $this->can('orders.manage')) {
            try { $out['orders_needs_review'] = (int)$this->db->fetchColumn('SELECT COUNT(*) FROM orders o WHERE ' . $this->needsReviewSql()); } catch (\Throwable) {}
        }
        if ($this->can('afa.view') || $this->can('afa.manage')) {
            $out['afa_processing']   = $q("SELECT COUNT(*) FROM afa_orders WHERE status = 'processing'");
            $out['afa_failed_today'] = $q("SELECT COUNT(*) FROM afa_orders WHERE status = 'failed' AND failed_at >= CURDATE()");
        }
        if ($this->can('shops.manage')) {
            $out['shops_pending']       = $q("SELECT COUNT(*) FROM shops WHERE status = 'pending'");
            $out['withdrawals_pending'] = $q("SELECT COUNT(*) FROM shop_withdrawals WHERE status = 'pending'");
        }
        if ($this->can('send_claim.view') || $this->can('send_claim.manage')) {
            $out['unclaimed_payments'] = $q("SELECT COUNT(*) FROM unclaimed_payments WHERE status = 'unclaimed'");
        }
        return $out;
    }

    /** Integer cursor: returns ['value'=>int,'fresh'=>bool]; fresh = initialised now (emit nothing). */
    private function idCursor(string $key, string $maxSql): array
    {
        $raw = $_GET[$key] ?? '';
        if ($raw === '' || !ctype_digit((string)$raw) || (int)$raw <= 0) {
            return ['value' => (int)$this->db->fetchColumn($maxSql), 'fresh' => true];
        }
        return ['value' => (int)$raw, 'fresh' => false];
    }

    /** Datetime cursor 'Y-m-d H:i:s'. Missing/invalid → $initial, fresh. Never more than 6h back. */
    private function timeCursor(string $key, string $initial, int $nowTs): array
    {
        $raw = (string)($_GET[$key] ?? '');
        if (!preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/', $raw)) {
            return ['value' => $initial, 'fresh' => true];
        }
        $floor = date('Y-m-d H:i:s', $nowTs - 6 * 3600);
        return ['value' => $raw < $floor ? $floor : $raw, 'fresh' => false];
    }

    private function event(string $type, int $refId, string $priority, string $title, string $body, ?string $at, int|string|null $uniq = null): array
    {
        return [
            'key'        => $type . ':' . ($uniq ?? $refId),
            'type'       => $type,
            'ref_id'     => $refId,
            'priority'   => $priority,
            'title'      => $title,
            'body'       => $body,
            'created_at' => $at,
        ];
    }

    private function formatComplaint(array $r): array
    {
        return [
            'id'              => (int)$r['id'],
            'order_id'        => (int)$r['order_id'],
            'status'          => $r['status'],
            'source'          => $r['source'] ?? 'web',
            'message'         => $r['message'],
            'admin_reply'     => $r['admin_reply'],
            'replied_at'      => $r['replied_at'],
            'created_at'      => $r['created_at'],
            'customer_name'   => ($r['contact_name'] ?? null) ?: ($r['user_name'] ?? null),
            'user_email'      => ($r['contact_email'] ?? null) ?: ($r['user_email'] ?? null),
            'order_reference' => $r['order_reference'] ?? null,
            'recipient'       => $r['order_recipient'] ?? null,
            'price'           => isset($r['order_price']) ? (float)$r['order_price'] : null,
            'network'         => $r['network_name'] ?? null,
            'bundle'          => $r['bundle_label'] ?? null,
        ];
    }

    private function formatOrder(array $o, bool $full = false): array
    {
        $flaggedReview = ($o['status'] ?? '') === 'processing' && (
            (!empty($o['provider_refunded_at']) && (empty($o['processing_at']) || (string)$o['provider_refunded_at'] >= (string)$o['processing_at']))
            || str_contains((string)($o['api_response'] ?? ''), '"needs_review":"provider_balance"')
        );
        $pii = $this->can('users.view_full_pii');
        $out = [
            'id'                 => (int)$o['id'],
            'reference'          => $o['reference'],
            'recipient'          => $pii ? $o['recipient'] : \App\Core\Mask::phone((string)$o['recipient']),
            'user_name'          => $o['user_name'] ?? null,
            'user_email'         => $pii ? ($o['user_email'] ?? null) : \App\Core\Mask::email((string)($o['user_email'] ?? '')),
            'network'            => $o['network_name'] ?? null,
            'bundle'             => $o['bundle_label'] ?? null,
            'capacity_gb'        => isset($o['capacity_gb']) ? (float)$o['capacity_gb'] : null,
            'price'              => (float)$o['price'],
            'status'             => $o['status'],
            'needs_review'       => $flaggedReview,
            'reprocessed'        => !empty($o['auto_reprocessed_at']),
            'source'             => $o['source'],
            'provider'           => $this->providerLabel($o['provider_name'] ?? null),
            'provider_reference' => $o['provider_reference'] ?? null,
            'provider_message'   => $this->apiMessage($o['api_response'] ?? null),
            'created_at'         => $o['created_at'],
            'accepted_at'        => $o['accepted_at'] ?? null,
            'failed_at'          => $o['failed_at'] ?? null,
            'delivered_at'       => $o['delivered_at'] ?? null,
            'refunded_at'        => $o['refunded_at'] ?? null,
        ];
        if ($full) {
            $out += [
                'idempotency_key'       => $o['idempotency_key'] ?? null,
                'payment_method'        => $o['payment_method'] ?? null,
                'momo_ref'              => $o['momo_ref'] ?? null,
                'balance_before'        => isset($o['balance_before']) ? (float)$o['balance_before'] : null,
                'balance_after'         => isset($o['balance_after']) ? (float)$o['balance_after'] : null,
                'user_phone'            => $pii ? ($o['user_phone'] ?? null) : \App\Core\Mask::phone((string)($o['user_phone'] ?? '')),
                'auto_reprocessed_from' => $this->providerLabel($o['auto_reprocessed_from'] ?? null),
                'auto_reprocessed_ref'  => $o['auto_reprocessed_ref'] ?? null,
                'api_response'          => !empty($o['api_response']) ? $this->pretty($o['api_response']) : null,
                'admin_note'            => $o['admin_note'] ?? null,
                'client_note'           => $o['client_note'] ?? null,
                'placed_at'             => $o['placed_at'] ?? null,
                'processing_at'         => $o['processing_at'] ?? null,
                'cancelled_at'          => $o['cancelled_at'] ?? null,
                'updated_at'            => $o['updated_at'] ?? null,
            ];
        }
        return $out;
    }

    private function formatAfa(array $a, bool $full = false): array
    {
        $pii = $this->can('users.view_full_pii');
        $out = [
            'id'                 => (int)$a['id'],
            'reference'          => $a['reference'],
            'full_name'          => $a['full_name'],
            'phone'              => $pii ? $a['phone'] : \App\Core\Mask::phone((string)$a['phone']),
            'ghana_card'         => $pii ? $a['ghana_card'] : \App\Core\Mask::ghanaCard((string)$a['ghana_card']),
            'location'           => $a['location'],
            'price'              => (float)$a['price'],
            'status'             => $a['status'],
            'needs_review'       => class_exists(\App\Helpers\AfaReview::class) && \App\Helpers\AfaReview::isFlagged($a),
            'source'             => $a['source'],
            'user_name'          => $a['user_name'] ?? null,
            'provider'           => $this->providerLabel($a['provider_name'] ?? null),
            'provider_reference' => $a['provider_reference'] ?? null,
            'provider_message'   => $this->apiMessage($a['api_response'] ?? null),
            'created_at'         => $a['created_at'],
        ];
        if ($full) {
            $out += [
                'user_email'      => $pii ? ($a['user_email'] ?? null) : \App\Core\Mask::email((string)($a['user_email'] ?? '')),
                'idempotency_key' => $a['idempotency_key'] ?? null,
                'payment_method'  => $a['payment_method'] ?? null,
                'momo_ref'        => $a['momo_ref'] ?? null,
                'api_response'    => !empty($a['api_response']) ? $this->pretty($a['api_response']) : null,
                'review_reason'   => $a['review_reason'] ?? null,
                'balance_before'  => isset($a['balance_before']) ? (float)$a['balance_before'] : null,
                'balance_after'   => isset($a['balance_after']) ? (float)$a['balance_after'] : null,
            ];
        }
        return $out;
    }

    /** Same "⚠️ Needs Review" rule as Admin → Orders (OrderAdminController::index). */
    private function needsReviewSql(): string
    {
        $delay = defined('QUEUE_REVIEW_DELAY_MINUTES') ? (int)QUEUE_REVIEW_DELAY_MINUTES : 10;
        $noRef = "(o.provider_reference IS NULL
                   AND ((o.processing_at IS NOT NULL AND o.processing_at < DATE_SUB(NOW(), INTERVAL {$delay} MINUTE))
                        OR COALESCE(o.api_response, '') LIKE '%\"needs_review\":\"provider_balance\"%'))";
        if (\App\Helpers\ProviderRefundHandler::ensureColumns($this->db)) {
            return "(o.status = 'processing' AND ({$noRef}
                     OR (o.provider_refunded_at IS NOT NULL
                         AND (o.processing_at IS NULL OR o.provider_refunded_at >= o.processing_at))))";
        }
        return "(o.status = 'processing' AND {$noRef})";
    }

    /** date_filter = all|today|yesterday|last7|custom (+ date_from/date_to), same as the website. */
    private function dateFilter(string $col): array
    {
        $f = trim((string)($_GET['date_filter'] ?? 'all'));
        $from = trim((string)($_GET['date_from'] ?? ''));
        $to   = trim((string)($_GET['date_to'] ?? ''));
        $ok   = fn($d) => (bool)preg_match('/^\d{4}-\d{2}-\d{2}$/', $d);
        switch ($f) {
            case 'today':     return [" AND {$col} >= ? AND {$col} < ?", [date('Y-m-d 00:00:00'), date('Y-m-d 00:00:00', strtotime('+1 day'))]];
            case 'yesterday': return [" AND {$col} >= ? AND {$col} < ?", [date('Y-m-d 00:00:00', strtotime('-1 day')), date('Y-m-d 00:00:00')]];
            case 'last7':     return [" AND {$col} >= ?", [date('Y-m-d 00:00:00', strtotime('-6 days'))]];
            case 'custom':
                $w = ''; $p = [];
                if ($ok($from)) { $w .= " AND {$col} >= ?"; $p[] = $from . ' 00:00:00'; }
                if ($ok($to))   { $w .= " AND {$col} < ?";  $p[] = date('Y-m-d 00:00:00', strtotime($to . ' +1 day')); }
                return [$w, $p];
        }
        return ['', []];
    }

    /** Provider names only for admins allowed to see providers (like the website). */
    private function providerLabel(?string $name): ?string
    {
        if ($name === null || $name === '') return null;
        if ($this->can('providers.view') || $this->can('providers.manage')) return $name;
        return 'Provider ' . strtoupper(substr(md5($name), 0, 2));
    }

    /** Pretty-prints a stored API response (handles JSON-in-JSON and arrays). */
    private function pretty(mixed $raw): string
    {
        $v = $raw;
        for ($i = 0; $i < 3 && is_string($v); $i++) {
            $d = json_decode($v, true);
            if ($d === null && json_last_error() !== JSON_ERROR_NONE) break;
            $v = $d;
        }
        if (is_string($v)) return $v;
        return (string)json_encode($v, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }

    /** Last admin-note line containing $needle (e.g. the reason an order went to review). */
    private function lastNoteLine(?string $note, string $needle): string
    {
        $lines = array_reverse(array_filter(array_map('trim', explode("\n", (string)$note))));
        foreach ($lines as $l) if (stripos($l, $needle) !== false) return $l;
        return $lines[0] ?? '';
    }

    private function idList(mixed $ids): array
    {
        if (is_string($ids)) $ids = explode(',', $ids);
        if (!is_array($ids)) return [];
        return array_values(array_unique(array_filter(array_map('intval', $ids), fn($i) => $i > 0)));
    }

    /**
     * Runs one of the WEBSITE's own admin actions (e.g. OrderAdminController::status)
     * as this admin, so the app uses exactly the same refund / claw-back / requeue /
     * commission / webhook code as the admin panel — nothing duplicated.
     *
     * Those actions finish with redirect() + a flash message. Here the admin is set
     * for this request only (the PHP session is closed without saving, no cookie is
     * sent), and the redirect is turned into a JSON reply with that flash message.
     */
    private function bridge(string $class, string $method, array $args, array $post, string $auditAction, string $auditType, string $auditId): never
    {
        if (session_status() === PHP_SESSION_ACTIVE) session_abort();   // nothing below is ever saved
        if (session_status() === PHP_SESSION_ACTIVE) @session_destroy();
        if (!headers_sent()) header_remove('Set-Cookie');
        $_SESSION = [
            'user_id'   => (int)$this->admin['id'],
            'user_role' => 'admin',
            'user_name' => (string)$this->admin['name'],
        ];
        $_POST = $post;
        $_SERVER['REQUEST_METHOD'] = 'POST';
        $_SERVER['HTTP_X_REQUESTED_WITH'] = '';   // website controllers reply with redirects, not their own JSON

        $self    = $this;
        $failure = null;
        register_shutdown_function(function () use (&$failure, $self, $auditAction, $auditType, $auditId) {
            $flash = $_SESSION['_flash'] ?? [];
            $err   = $failure ?? ($flash['error'] ?? null);
            $ok    = $flash['success'] ?? null;
            $fatal = error_get_last();
            if (!$err && !$ok && $fatal && in_array($fatal['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR], true)) {
                error_log('[AdminAlertApp bridge] ' . $fatal['message'] . ' in ' . $fatal['file'] . ':' . $fatal['line']);
                $err = 'The website could not finish this action. Check it on the admin panel.';
            }
            while (ob_get_level() > 0) ob_end_clean();
            if (!headers_sent()) {
                header_remove('Location');
                header_remove('Set-Cookie');
                header('Content-Type: application/json; charset=utf-8');
                header('Cache-Control: no-store');
                http_response_code($err ? 422 : 200);
            }
            $self->auditFromBridge($auditAction, $auditType, $auditId, $err ? 'failed' : 'success', (string)($err ?? $ok ?? ''));
            echo json_encode(['success' => !$err, 'message' => (string)($err ?? $ok ?? 'Done.')], JSON_UNESCAPED_UNICODE);
            $_SESSION = [];
        });

        ob_start();
        try {
            (new $class())->$method(...$args);
        } catch (\Throwable $e) {
            error_log('[AdminAlertApp bridge] ' . get_class($e) . ': ' . $e->getMessage() . ' in ' . $e->getFile() . ':' . $e->getLine());
            $failure = 'Action failed: ' . $e->getMessage();
        }
        exit;
    }

    /**
     * The router opens a PHP session for every request. The app never uses it,
     * so drop it (no session file, no cookie) — otherwise polling every few
     * seconds would leave thousands of empty session files on the server.
     */
    private function dropWebSession(): void
    {
        if (session_status() === PHP_SESSION_ACTIVE) {
            $_SESSION = [];
            @session_destroy();
        }
        if (!headers_sent()) header_remove('Set-Cookie');
    }

    /** Called from bridge()'s shutdown handler. */
    public function auditFromBridge(string $action, string $type, string $id, string $status, string $message): void
    {
        $this->audit($action, $type, $id, ['via' => 'admin_app', 'result' => mb_substr($message, 0, 300)], $status);
    }

    /** Provider failure text; handles the double-json-encoded rows (see AdminAppController). */
    private function apiMessage(?string $raw): ?string
    {
        if (!$raw) return null;
        $d = json_decode($raw, true);
        if (is_string($d)) $d = json_decode($d, true);
        if (!is_array($d)) return null;
        $m = $d['message'] ?? $d['error'] ?? null;
        return is_string($m) ? $m : null;
    }

    private function audit(string $action, ?string $type, ?string $id, array $meta = [], string $status = 'success'): void
    {
        try {
            $this->db->execute(
                'INSERT INTO admin_audit_log
                    (admin_id, admin_name, action, target_type, target_id, status, meta_json, ip_address, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())',
                [(int)$this->admin['id'], $this->admin['name'] ?? null, $action, $type, $id, $status,
                 $meta ? json_encode($meta) : null, $this->clientIp()]
            );
        } catch (\Throwable $e) {
            error_log('[AdminAlertApp audit] ' . $e->getMessage());
        }
    }

    private function body(): array
    {
        static $cache = null;
        if ($cache !== null) return $cache;
        $json  = json_decode((string)file_get_contents('php://input'), true);
        $cache = is_array($json) ? $json + $_POST : $_POST;
        return $cache;
    }

    private function pageParams(int $default = 20, int $max = 50): array
    {
        $page  = max(1, (int)($_GET['page'] ?? 1));
        $limit = min($max, max(1, (int)($_GET['limit'] ?? $default)));
        return [$page, $limit, ($page - 1) * $limit];
    }

    private function pagination(int $total, int $page, int $limit): array
    {
        return ['total' => $total, 'page' => $page, 'limit' => $limit, 'total_pages' => (int)ceil($total / max(1, $limit))];
    }

    private function clip(?string $s, int $len = 220): string
    {
        $s = trim(preg_replace('/\s+/u', ' ', (string)$s) ?? '');
        return mb_strlen($s) > $len ? mb_substr($s, 0, $len - 1) . '…' : $s;
    }

    private function clientIp(): string
    {
        $ip = $_SERVER['HTTP_X_FORWARDED_FOR'] ?? $_SERVER['REMOTE_ADDR'] ?? '';
        return mb_substr(trim(explode(',', $ip)[0]), 0, 45);
    }

    private function fail(string $message, int $code): never
    {
        $this->json(['success' => false, 'message' => $message], $code);
        exit;
    }

    /** Self-installs the devices table on first sign-in (the SQL file does the same). */
    private function ensureTables(): void
    {
        try {
            $this->db->execute(
                'CREATE TABLE IF NOT EXISTS admin_app_devices (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    admin_user_id INT UNSIGNED NOT NULL,
                    token_hash CHAR(64) NOT NULL,
                    device_name VARCHAR(120) DEFAULT NULL,
                    app_version VARCHAR(20) DEFAULT NULL,
                    status ENUM("active","revoked") NOT NULL DEFAULT "active",
                    last_ip VARCHAR(45) DEFAULT NULL,
                    last_seen_at DATETIME DEFAULT NULL,
                    revoked_at DATETIME DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uq_token_hash (token_hash),
                    KEY idx_admin (admin_user_id, status)
                 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
            );
        } catch (\Throwable $e) {
            error_log('[AdminAlertApp ensureTables] ' . $e->getMessage());
        }
    }
}
