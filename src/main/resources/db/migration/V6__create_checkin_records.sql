-- Each accepted scan is an immutable attempt. Credentials are represented only by their SHA-256 digest.
CREATE TABLE checkin_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checker_id BIGINT NOT NULL,
    submitted_session_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    ticket_id BIGINT NULL,
    credential_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempted_at DATETIME(6) NOT NULL,
    result VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    successful_ticket_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN result='SUCCESS' THEN ticket_id ELSE NULL END) STORED,
    CONSTRAINT uk_checkin_request UNIQUE (request_id),
    CONSTRAINT uk_checkin_success UNIQUE (successful_ticket_id),
    INDEX ix_checkin_session_time (session_id,attempted_at,id),
    INDEX ix_checkin_ticket_time (ticket_id,attempted_at,id),
    CONSTRAINT fk_checkin_checker FOREIGN KEY (checker_id) REFERENCES account(id),
    CONSTRAINT fk_checkin_session FOREIGN KEY (session_id) REFERENCES session(id),
    CONSTRAINT fk_checkin_ticket FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    CONSTRAINT ck_checkin_input CHECK (submitted_session_id>0 AND CHAR_LENGTH(request_id)=36),
    CONSTRAINT ck_checkin_digest CHECK (credential_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_checkin_result CHECK (result IN ('SUCCESS','ALREADY_CHECKED_IN','REFUNDING','REFUNDED',
        'INVALID_CREDENTIAL','WRONG_SESSION','TOO_EARLY','TOO_LATE','SESSION_NOT_FOUND','INCONSISTENT_STATE')),
    CONSTRAINT ck_checkin_session CHECK (
        (result='SESSION_NOT_FOUND' AND session_id IS NULL AND ticket_id IS NULL)
        OR (result<>'SESSION_NOT_FOUND' AND session_id IS NOT NULL AND session_id=submitted_session_id)),
    CONSTRAINT ck_checkin_ticket CHECK (result IN ('SESSION_NOT_FOUND','INVALID_CREDENTIAL') OR ticket_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
