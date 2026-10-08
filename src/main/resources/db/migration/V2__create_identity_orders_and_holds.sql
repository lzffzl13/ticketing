-- Identity, order snapshots and grouped seat ownership. All timestamps are application UTC.
CREATE TABLE account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_account_login UNIQUE (login_name),
    CONSTRAINT ck_account_login CHECK (CHAR_LENGTH(login_name) > 0 AND login_name = LOWER(login_name)),
    CONSTRAINT ck_account_password CHECK (CHAR_LENGTH(password_hash) > 0),
    CONSTRAINT ck_account_role CHECK (role IN ('CONSUMER', 'OPERATOR', 'CHECKER')),
    CONSTRAINT ck_account_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE demo_login_token (
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX ix_token_account_expiry (account_id, expires_at),
    INDEX ix_token_expiry (expires_at),
    CONSTRAINT fk_token_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_token_window CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    customer_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    selection_mode VARCHAR(16) NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    payment_expires_at DATETIME(6) NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_order_number UNIQUE (order_number),
    CONSTRAINT uk_order_customer_request UNIQUE (customer_id, idempotency_key),
    CONSTRAINT uk_order_session_id UNIQUE (session_id, id),
    INDEX ix_order_customer_created (customer_id, created_at, id),
    INDEX ix_order_status_expiry (order_status, payment_expires_at, id),
    CONSTRAINT fk_order_account FOREIGN KEY (customer_id) REFERENCES account (id),
    CONSTRAINT fk_order_session FOREIGN KEY (session_id) REFERENCES session (id),
    CONSTRAINT ck_order_mode CHECK (selection_mode IN ('MANUAL', 'RECOMMENDED')),
    CONSTRAINT ck_order_status CHECK (order_status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED',
        'PARTIALLY_REFUNDED', 'FULLY_REFUNDED', 'COMPLETED')),
    CONSTRAINT ck_order_amount CHECK (total_amount > 0),
    CONSTRAINT ck_order_window CHECK (payment_expires_at > created_at),
    CONSTRAINT ck_order_request_key CHECK (CHAR_LENGTH(idempotency_key) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE session_seat ADD CONSTRAINT uk_snapshot_session_id UNIQUE (session_id, id);

CREATE TABLE order_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    session_seat_id BIGINT NOT NULL,
    area_code_snapshot VARCHAR(64) NOT NULL,
    area_name_snapshot VARCHAR(128) NOT NULL,
    row_label_snapshot VARCHAR(32) NOT NULL,
    seat_number_snapshot INT NOT NULL,
    price_tier_id BIGINT NOT NULL,
    price_tier_name_snapshot VARCHAR(128) NOT NULL,
    transaction_price DECIMAL(10,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_order_item_seat UNIQUE (order_id, session_seat_id),
    CONSTRAINT fk_item_order FOREIGN KEY (session_id, order_id) REFERENCES customer_order (session_id, id),
    CONSTRAINT fk_item_seat FOREIGN KEY (session_id, session_seat_id) REFERENCES session_seat (session_id, id),
    CONSTRAINT fk_item_tier FOREIGN KEY (session_id, price_tier_id) REFERENCES price_tier (session_id, id),
    CONSTRAINT ck_item_number CHECK (seat_number_snapshot > 0),
    CONSTRAINT ck_item_price CHECK (transaction_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE seat_hold (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    hold_status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_hold_order UNIQUE (order_id),
    CONSTRAINT uk_hold_session_id UNIQUE (session_id, id),
    INDEX ix_hold_status_expiry (hold_status, expires_at, id),
    CONSTRAINT fk_hold_order FOREIGN KEY (session_id, order_id) REFERENCES customer_order (session_id, id),
    CONSTRAINT ck_hold_status CHECK (hold_status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_hold_window CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE seat_hold_item (
    hold_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    session_seat_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (hold_id, session_seat_id),
    INDEX ix_hold_item_seat (session_id, session_seat_id),
    CONSTRAINT fk_hold_item_hold FOREIGN KEY (session_id, hold_id) REFERENCES seat_hold (session_id, id),
    CONSTRAINT fk_hold_item_seat FOREIGN KEY (session_id, session_seat_id) REFERENCES session_seat (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE session_seat
    ADD COLUMN current_hold_id BIGINT NULL,
    ADD CONSTRAINT fk_snapshot_current_hold FOREIGN KEY (session_id, current_hold_id)
        REFERENCES seat_hold (session_id, id),
    ADD CONSTRAINT ck_snapshot_current_hold CHECK (
        (seat_status = 'HELD' AND current_hold_id IS NOT NULL)
        OR (seat_status <> 'HELD' AND current_hold_id IS NULL)
    );
