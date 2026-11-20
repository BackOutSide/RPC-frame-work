CREATE TABLE shops (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255) NOT NULL,
    description VARCHAR(1000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE vouchers (
    id BIGINT NOT NULL PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    face_value_cents BIGINT NOT NULL,
    starts_at_epoch_millis BIGINT NOT NULL,
    ends_at_epoch_millis BIGINT NOT NULL,
    stock_remaining INT NOT NULL,
    CONSTRAINT fk_vouchers_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
    INDEX idx_vouchers_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE voucher_orders (
    order_id VARCHAR(36) NOT NULL PRIMARY KEY,
    voucher_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_orders_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id),
    CONSTRAINT uk_orders_voucher_user UNIQUE (voucher_id, user_id),
    INDEX idx_orders_user (user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO shops (id, name, address, description) VALUES
    (1, '云端咖啡馆', '上海市静安区示例路 18 号', '供本地压测使用的示例商户'),
    (2, '小街面馆', '上海市徐汇区示例路 26 号', '供商户列表和缓存测试使用的示例商户');

INSERT INTO vouchers
    (id, shop_id, title, face_value_cents, starts_at_epoch_millis, ends_at_epoch_millis, stock_remaining)
VALUES
    (1, 1, '云端咖啡馆限量代金券', 2000,
     CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) - 86400000,
     CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED) + 2592000000,
     20);
