CREATE TABLE IF NOT EXISTS shows (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       name VARCHAR(100) NOT NULL,
                       price_paise BIGINT NOT NULL,
                       per_user_limit INT NOT NULL DEFAULT 4
);

CREATE TABLE IF NOT EXISTS seats (
                       show_id BIGINT NOT NULL,
                       seat_no VARCHAR(20) NOT NULL,
                       status ENUM('available','held','confirmed') NOT NULL DEFAULT 'available',
                       user_id VARCHAR(64) NULL,
                       reservation_id VARCHAR(64) NULL,
                       PRIMARY KEY (show_id, seat_no)
);

CREATE TABLE IF NOT EXISTS reservations (
                              id VARCHAR(64) PRIMARY KEY,
                              show_id BIGINT NOT NULL,
                              user_id VARCHAR(64) NOT NULL,
                              seats VARCHAR(200) NOT NULL,
                              amount_paise BIGINT NOT NULL,
                              status ENUM('confirmed','cancelled') NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
                                                user_id VARCHAR(64) NOT NULL,
    show_id BIGINT NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, show_id, idem_key)
    );

CREATE TABLE IF NOT EXISTS user_show_quota (
                                 show_id BIGINT NOT NULL,
                                 user_id VARCHAR(64) NOT NULL,
                                 held INT NOT NULL DEFAULT 0,
                                 PRIMARY KEY (show_id, user_id)
);