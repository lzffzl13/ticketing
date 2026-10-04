-- UTC timestamps are provided by the application Clock, never by local NOW().
CREATE TABLE venue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_venue_code UNIQUE (code),
    CONSTRAINT ck_venue_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_venue_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE area (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    display_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_area_venue_code UNIQUE (venue_id, code),
    CONSTRAINT fk_area_venue FOREIGN KEY (venue_id) REFERENCES venue (id),
    CONSTRAINT ck_area_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_area_order CHECK (display_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE seat_template (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    area_id BIGINT NOT NULL,
    row_label VARCHAR(32) NOT NULL,
    row_order INT NOT NULL,
    seat_number INT NOT NULL,
    coordinate_x DECIMAL(10,3) NOT NULL,
    coordinate_y DECIMAL(10,3) NOT NULL,
    aisle_segment VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_template_position UNIQUE (area_id, row_label, seat_number),
    CONSTRAINT fk_template_area FOREIGN KEY (area_id) REFERENCES area (id),
    CONSTRAINT ck_template_row CHECK (CHAR_LENGTH(TRIM(row_label)) > 0 AND row_order >= 0),
    CONSTRAINT ck_template_number CHECK (seat_number > 0),
    CONSTRAINT ck_template_segment CHECK (CHAR_LENGTH(TRIM(aisle_segment)) > 0),
    CONSTRAINT ck_template_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(2000) NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_event_code UNIQUE (code),
    CONSTRAINT ck_event_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_event_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    event_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    event_name_snapshot VARCHAR(128) NOT NULL,
    venue_name_snapshot VARCHAR(128) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    admission_open_at DATETIME(6) NOT NULL,
    admission_close_at DATETIME(6) NOT NULL,
    sales_status VARCHAR(16) NOT NULL,
    first_opened_at DATETIME(6) NULL,
    forbid_new_orphan BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_session_code UNIQUE (code),
    INDEX ix_session_event_start (event_id, starts_at),
    INDEX ix_session_venue_start (venue_id, starts_at),
    CONSTRAINT fk_session_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT fk_session_venue FOREIGN KEY (venue_id) REFERENCES venue (id),
    CONSTRAINT ck_session_window CHECK (admission_open_at < admission_close_at AND admission_close_at <= starts_at),
    CONSTRAINT ck_session_status CHECK (sales_status IN ('DRAFT', 'ON_SALE', 'STOPPED')),
    CONSTRAINT ck_session_opened CHECK (
        (sales_status = 'DRAFT' AND first_opened_at IS NULL)
        OR (sales_status IN ('ON_SALE', 'STOPPED') AND first_opened_at IS NOT NULL)
    ),
    CONSTRAINT ck_session_orphan CHECK (forbid_new_orphan IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE price_tier (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_tier_session_code UNIQUE (session_id, code),
    CONSTRAINT uk_tier_session_id UNIQUE (session_id, id),
    CONSTRAINT fk_tier_session FOREIGN KEY (session_id) REFERENCES session (id),
    CONSTRAINT ck_tier_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_tier_price CHECK (price > 0),
    CONSTRAINT ck_tier_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE session_seat (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    source_seat_template_id BIGINT NOT NULL,
    source_area_id BIGINT NOT NULL,
    area_code_snapshot VARCHAR(64) NOT NULL,
    area_name_snapshot VARCHAR(128) NOT NULL,
    area_display_order_snapshot INT NOT NULL,
    row_label_snapshot VARCHAR(32) NOT NULL,
    row_order_snapshot INT NOT NULL,
    seat_number_snapshot INT NOT NULL,
    coordinate_x_snapshot DECIMAL(10,3) NOT NULL,
    coordinate_y_snapshot DECIMAL(10,3) NOT NULL,
    aisle_segment_snapshot VARCHAR(32) NOT NULL,
    price_tier_id BIGINT NULL,
    price_tier_name_snapshot VARCHAR(128) NULL,
    price_snapshot DECIMAL(10,2) NULL,
    seat_status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_snapshot_source UNIQUE (session_id, source_seat_template_id),
    CONSTRAINT uk_snapshot_position UNIQUE (session_id, area_code_snapshot, row_label_snapshot, seat_number_snapshot),
    INDEX ix_snapshot_display (session_id, area_display_order_snapshot, row_order_snapshot, seat_number_snapshot),
    INDEX ix_snapshot_tier (session_id, price_tier_id),
    CONSTRAINT fk_snapshot_session FOREIGN KEY (session_id) REFERENCES session (id),
    CONSTRAINT fk_snapshot_tier FOREIGN KEY (session_id, price_tier_id) REFERENCES price_tier (session_id, id),
    CONSTRAINT ck_snapshot_status CHECK (seat_status IN ('UNAVAILABLE', 'AVAILABLE', 'HELD', 'SOLD')),
    CONSTRAINT ck_snapshot_position CHECK (
        CHAR_LENGTH(TRIM(area_code_snapshot)) > 0 AND CHAR_LENGTH(TRIM(area_name_snapshot)) > 0
        AND CHAR_LENGTH(TRIM(row_label_snapshot)) > 0 AND CHAR_LENGTH(TRIM(aisle_segment_snapshot)) > 0
        AND area_display_order_snapshot >= 0 AND row_order_snapshot >= 0 AND seat_number_snapshot > 0
    ),
    CONSTRAINT ck_snapshot_price CHECK (
        (price_tier_id IS NULL AND price_tier_name_snapshot IS NULL AND price_snapshot IS NULL AND seat_status = 'UNAVAILABLE')
        OR (price_tier_id IS NOT NULL AND price_tier_name_snapshot IS NOT NULL AND price_snapshot IS NOT NULL
            AND CHAR_LENGTH(TRIM(price_tier_name_snapshot)) > 0 AND price_snapshot > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
