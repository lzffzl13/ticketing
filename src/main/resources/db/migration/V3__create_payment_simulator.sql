-- Simulated channel facts commit independently of merchant orders and tickets.
-- RESERVED is an uncommitted row-claim step, never a returned channel result.
CREATE TABLE simulated_payment (
    payment_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    merchant_order_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    payment_expires_at DATETIME(6) NOT NULL,
    payment_status VARCHAR(16) NOT NULL,
    accepted_at DATETIME(6) NULL,
    result_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_simulated_payment_number CHECK (CHAR_LENGTH(payment_number) > 0),
    CONSTRAINT ck_simulated_merchant_order CHECK (CHAR_LENGTH(merchant_order_number) > 0),
    CONSTRAINT ck_simulated_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_simulated_payment_status CHECK (payment_status IN
        ('RESERVED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
    CONSTRAINT ck_simulated_acceptance_window CHECK
        (accepted_at IS NULL OR accepted_at < payment_expires_at),
    CONSTRAINT ck_simulated_payment_lifecycle CHECK (
        (payment_status = 'RESERVED' AND accepted_at IS NULL AND result_at IS NULL)
        OR (payment_status = 'PROCESSING' AND accepted_at IS NOT NULL AND result_at IS NULL)
        OR (payment_status = 'SUCCEEDED' AND accepted_at IS NOT NULL AND result_at IS NOT NULL)
        OR (payment_status IN ('FAILED', 'CLOSED') AND result_at IS NOT NULL)
    ),
    CONSTRAINT ck_simulated_failure_code CHECK (
        (payment_status = 'FAILED' AND failure_code IS NOT NULL AND CHAR_LENGTH(failure_code) > 0)
        OR (payment_status <> 'FAILED' AND failure_code IS NULL)
    ),
    CONSTRAINT ck_simulated_result_time CHECK
        (accepted_at IS NULL OR result_at IS NULL OR result_at >= accepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE simulated_notification (
    notification_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    payment_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_status VARCHAR(16) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    delivered_at DATETIME(6) NULL,
    INDEX ix_simulated_notification_pending (delivered_at, observed_at, notification_number),
    INDEX ix_simulated_notification_payment (payment_number, observed_at, notification_number),
    CONSTRAINT fk_simulated_notification_payment FOREIGN KEY (payment_number)
        REFERENCES simulated_payment (payment_number),
    CONSTRAINT ck_simulated_notification_number CHECK (CHAR_LENGTH(notification_number) > 0),
    CONSTRAINT ck_simulated_notification_status CHECK (observed_status IN
        ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
    CONSTRAINT ck_simulated_notification_delivery CHECK
        (delivered_at IS NULL OR delivered_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
