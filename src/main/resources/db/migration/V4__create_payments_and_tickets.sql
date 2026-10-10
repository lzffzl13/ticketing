-- Local transaction facts. Simulator V3 remains independent; timestamps are UTC.
ALTER TABLE customer_order
    ADD COLUMN cancellation_requested_at DATETIME(6) NULL,
    ADD COLUMN cancellation_reason VARCHAR(16) NULL,
    ADD COLUMN first_unresolved_at DATETIME(6) NULL,
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN next_recovery_at DATETIME(6) NULL,
    ADD COLUMN recent_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN manual_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN recovery_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recovery_claim_until DATETIME(6) NULL,
    ADD INDEX ix_order_recovery (order_status, manual_required, next_recovery_at, id),
    ADD CONSTRAINT ck_order_cancellation CHECK (
        (cancellation_requested_at IS NULL AND cancellation_reason IS NULL)
        OR (cancellation_requested_at IS NOT NULL AND cancellation_reason IS NOT NULL
            AND cancellation_reason IN ('USER','TIMEOUT'))),
    ADD CONSTRAINT ck_order_recovery_count CHECK (consecutive_failures >= 0),
    ADD CONSTRAINT ck_order_recovery_manual CHECK (manual_required IN (0,1)),
    ADD CONSTRAINT ck_order_recovery_claim CHECK (
        (recovery_token IS NULL AND recovery_claim_until IS NULL)
        OR (recovery_token IS NOT NULL AND CHAR_LENGTH(recovery_token)>0 AND recovery_claim_until IS NOT NULL));

CREATE TABLE payment_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    payment_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    payment_expires_at DATETIME(6) NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_status VARCHAR(16) NOT NULL,
    accepted_at DATETIME(6) NULL,
    result_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    occupied_order_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN payment_status IN ('INITIATED','PROCESSING','SUCCEEDED') THEN order_id ELSE NULL END) STORED,
    CONSTRAINT uk_payment_number UNIQUE (payment_number),
    CONSTRAINT uk_payment_request UNIQUE (order_id,request_key),
    CONSTRAINT uk_payment_occupied_order UNIQUE (occupied_order_id),
    INDEX ix_payment_order (order_id,id),
    CONSTRAINT fk_payment_order FOREIGN KEY (session_id,order_id) REFERENCES customer_order(session_id,id),
    CONSTRAINT ck_payment_identifiers CHECK (CHAR_LENGTH(payment_number)>0 AND CHAR_LENGTH(request_key)>0),
    CONSTRAINT ck_payment_amount CHECK (amount>0),
    CONSTRAINT ck_payment_acceptance CHECK (accepted_at IS NULL OR accepted_at<payment_expires_at),
    CONSTRAINT ck_payment_status CHECK (payment_status IN ('INITIATED','PROCESSING','SUCCEEDED','FAILED','CLOSED')),
    CONSTRAINT ck_payment_lifecycle CHECK (
        (payment_status='INITIATED' AND accepted_at IS NULL AND result_at IS NULL)
        OR (payment_status='PROCESSING' AND accepted_at IS NOT NULL AND result_at IS NULL)
        OR (payment_status='SUCCEEDED' AND accepted_at IS NOT NULL AND result_at IS NOT NULL)
        OR (payment_status IN ('FAILED','CLOSED') AND result_at IS NOT NULL)),
    CONSTRAINT ck_payment_failure CHECK (
        (payment_status='FAILED' AND failure_code IS NOT NULL AND CHAR_LENGTH(failure_code)>0)
        OR (payment_status<>'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_payment_result_time CHECK (accepted_at IS NULL OR result_at IS NULL OR result_at>=accepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE order_item ADD CONSTRAINT uk_order_item_ticket_binding
    UNIQUE (id,order_id,session_id,session_seat_id);

CREATE TABLE ticket (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_item_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    session_seat_id BIGINT NOT NULL,
    ticket_status VARCHAR(16) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    occupying_seat_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN ticket_status IN ('VALID','REFUNDING','CHECKED_IN') THEN session_seat_id ELSE NULL END) STORED,
    CONSTRAINT uk_ticket_number UNIQUE (ticket_number),
    CONSTRAINT uk_ticket_order_item UNIQUE (order_item_id),
    CONSTRAINT uk_ticket_occupying_seat UNIQUE (occupying_seat_id),
    INDEX ix_ticket_order (order_id,session_seat_id),
    CONSTRAINT fk_ticket_item_binding FOREIGN KEY (order_item_id,order_id,session_id,session_seat_id)
        REFERENCES order_item(id,order_id,session_id,session_seat_id),
    CONSTRAINT ck_ticket_number CHECK (CHAR_LENGTH(ticket_number)>0),
    CONSTRAINT ck_ticket_status CHECK (ticket_status IN ('VALID','REFUNDING','REFUNDED','CHECKED_IN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
