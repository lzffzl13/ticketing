-- Single-ticket refunds keep local business facts separate from independently committed channel facts.
ALTER TABLE customer_order ADD CONSTRAINT uk_order_refund_customer_binding
    UNIQUE (id,customer_id,session_id);
ALTER TABLE ticket ADD CONSTRAINT uk_ticket_refund_binding
    UNIQUE (id,ticket_number,order_item_id,order_id,session_id,session_seat_id);
ALTER TABLE payment_record ADD CONSTRAINT uk_payment_refund_binding
    UNIQUE (id,payment_number,order_id,session_id);

CREATE TABLE refund_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    refund_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    session_seat_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    ticket_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id BIGINT NOT NULL,
    payment_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_status VARCHAR(16) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    result_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    first_unresolved_at DATETIME(6) NULL,
    next_recovery_at DATETIME(6) NULL,
    recovery_claim_until DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    recent_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    manual_required BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occupied_ticket_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN refund_status IN ('REQUESTED','PROCESSING','SUCCEEDED') THEN ticket_id ELSE NULL END) STORED,
    CONSTRAINT uk_refund_number UNIQUE (refund_number),
    CONSTRAINT uk_refund_customer_request UNIQUE (customer_id,request_key),
    CONSTRAINT uk_refund_occupied_ticket UNIQUE (occupied_ticket_id),
    INDEX ix_refund_order (order_id,id),
    INDEX ix_refund_payment (payment_id,id),
    INDEX ix_refund_recovery (manual_required,next_recovery_at,id),
    CONSTRAINT fk_refund_customer_order FOREIGN KEY (order_id,customer_id,session_id)
        REFERENCES customer_order(id,customer_id,session_id),
    CONSTRAINT fk_refund_ticket_binding FOREIGN KEY
        (ticket_id,ticket_number,order_item_id,order_id,session_id,session_seat_id)
        REFERENCES ticket(id,ticket_number,order_item_id,order_id,session_id,session_seat_id),
    CONSTRAINT fk_refund_payment_binding FOREIGN KEY (payment_id,payment_number,order_id,session_id)
        REFERENCES payment_record(id,payment_number,order_id,session_id),
    CONSTRAINT ck_refund_identifiers CHECK
        (CHAR_LENGTH(refund_number)>0 AND CHAR_LENGTH(request_key)>0),
    CONSTRAINT ck_refund_amount CHECK (amount>0),
    CONSTRAINT ck_refund_status CHECK (refund_status IN ('REQUESTED','PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_refund_lifecycle CHECK (
        (refund_status='REQUESTED' AND accepted_at IS NULL AND result_at IS NULL)
        OR (refund_status='PROCESSING' AND accepted_at IS NOT NULL AND result_at IS NULL)
        OR (refund_status IN ('SUCCEEDED','FAILED') AND accepted_at IS NOT NULL AND result_at IS NOT NULL)),
    CONSTRAINT ck_refund_failure CHECK (
        (refund_status='FAILED' AND failure_code IS NOT NULL AND CHAR_LENGTH(failure_code)>0)
        OR (refund_status<>'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_refund_result_time CHECK (result_at IS NULL OR result_at>=accepted_at),
    CONSTRAINT ck_refund_recovery_count CHECK (consecutive_failures>=0),
    CONSTRAINT ck_refund_recovery_manual CHECK (manual_required IN (0,1)),
    CONSTRAINT ck_refund_recovery_claim CHECK (
        (recovery_token IS NULL AND recovery_claim_until IS NULL)
        OR (recovery_token IS NOT NULL AND CHAR_LENGTH(recovery_token)>0 AND recovery_claim_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE simulated_payment ADD CONSTRAINT uk_simulated_payment_refund_binding
    UNIQUE (payment_number,merchant_order_number);

CREATE TABLE simulator_refund (
    refund_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    payment_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_order_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    refund_status VARCHAR(16) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    result_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_simulator_refund_binding
        UNIQUE (refund_number,payment_number,merchant_order_number,ticket_number,amount),
    INDEX ix_simulator_refund_payment (payment_number,refund_status,refund_number),
    CONSTRAINT fk_simulator_refund_payment FOREIGN KEY (payment_number,merchant_order_number)
        REFERENCES simulated_payment(payment_number,merchant_order_number),
    CONSTRAINT ck_simulator_refund_identifiers CHECK
        (CHAR_LENGTH(refund_number)>0 AND CHAR_LENGTH(ticket_number)>0),
    CONSTRAINT ck_simulator_refund_amount CHECK (amount>0),
    CONSTRAINT ck_simulator_refund_status CHECK (refund_status IN ('PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_simulator_refund_lifecycle CHECK (
        (refund_status='PROCESSING' AND result_at IS NULL)
        OR (refund_status IN ('SUCCEEDED','FAILED') AND result_at IS NOT NULL)),
    CONSTRAINT ck_simulator_refund_failure CHECK (
        (refund_status='FAILED' AND failure_code IS NOT NULL AND CHAR_LENGTH(failure_code)>0)
        OR (refund_status<>'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_simulator_refund_result_time CHECK (result_at IS NULL OR result_at>=accepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every row holds the accepted/terminal fact as it was observed, never a live join to a later status.
CREATE TABLE simulator_refund_notification (
    notification_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    refund_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_order_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_number VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    refund_status VARCHAR(16) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    result_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    notified_at DATETIME(6) NOT NULL,
    delivered_at DATETIME(6) NULL,
    CONSTRAINT uk_simulator_refund_notification_status UNIQUE (refund_number,refund_status),
    INDEX ix_simulator_refund_notification_pending (delivered_at,notified_at,notification_number),
    INDEX ix_simulator_refund_notification_refund (refund_number,notified_at,notification_number),
    CONSTRAINT fk_simulator_refund_notification_binding FOREIGN KEY
        (refund_number,payment_number,merchant_order_number,ticket_number,amount)
        REFERENCES simulator_refund(refund_number,payment_number,merchant_order_number,ticket_number,amount),
    CONSTRAINT ck_simulator_refund_notification_number CHECK (CHAR_LENGTH(notification_number)>0),
    CONSTRAINT ck_simulator_refund_notification_status CHECK (refund_status IN ('PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_simulator_refund_notification_lifecycle CHECK (
        (refund_status='PROCESSING' AND result_at IS NULL)
        OR (refund_status IN ('SUCCEEDED','FAILED') AND result_at IS NOT NULL)),
    CONSTRAINT ck_simulator_refund_notification_failure CHECK (
        (refund_status='FAILED' AND failure_code IS NOT NULL AND CHAR_LENGTH(failure_code)>0)
        OR (refund_status<>'FAILED' AND failure_code IS NULL)),
    CONSTRAINT ck_simulator_refund_notification_result_time CHECK (result_at IS NULL OR result_at>=accepted_at),
    CONSTRAINT ck_simulator_refund_notification_time CHECK
        (notified_at>=accepted_at AND (result_at IS NULL OR notified_at>=result_at)),
    CONSTRAINT ck_simulator_refund_notification_delivery CHECK
        (delivered_at IS NULL OR delivered_at>=notified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
