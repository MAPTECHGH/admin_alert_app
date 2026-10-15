-- MAPTECH ADMIN ALERTS app — one row per signed-in admin phone.
-- Run once in phpMyAdmin (the API also creates it automatically on first sign-in).
CREATE TABLE IF NOT EXISTS `admin_app_devices` (
  `id`            INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `admin_user_id` INT UNSIGNED NOT NULL,
  `token_hash`    CHAR(64)     NOT NULL COMMENT 'sha256 of the app token; the token itself is never stored',
  `device_name`   VARCHAR(120) DEFAULT NULL,
  `app_version`   VARCHAR(20)  DEFAULT NULL,
  `status`        ENUM('active','revoked') NOT NULL DEFAULT 'active',
  `last_ip`       VARCHAR(45)  DEFAULT NULL,
  `last_seen_at`  DATETIME     DEFAULT NULL,
  `revoked_at`    DATETIME     DEFAULT NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uq_token_hash` (`token_hash`),
  KEY `idx_admin` (`admin_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
