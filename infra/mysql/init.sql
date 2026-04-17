-- Shopping Search PoC — MySQL 초기 스키마
--
-- 설계 의도:
--   - Debezium CDC 대상 3개 테이블. products가 중심, brands/categories는 lookup.
--   - Enricher가 brand_id/category_id로 join 해 ES 문서를 조립한다.
--   - Debezium은 ROW 단위로 이벤트를 내보내므로 이 구조가 그대로 topic 3개가 된다:
--       dbserver1.shopping.products
--       dbserver1.shopping.brands
--       dbserver1.shopping.categories
--   - brands 테이블이 바뀌면 products의 brand_name만 바뀐 문서를 재색인해야 하므로
--     Enricher는 brands CDC 이벤트를 받아 영향받는 products를 역조회하는 로직이 필요.
--     (이게 "간접 변경"의 전형 — docs/indexing-strategy.md의 정적/동적 색인 조합 근거)

-- my.cnf 에서 init-connect + skip-character-set-client-handshake 로 강제하지만,
-- init.sql 레벨에서도 방어적으로 명시. docs/incidents/2026-04-17-korean-encoding-fix.md 참조.
SET NAMES utf8mb4;

USE shopping;

-- Debezium 전용 계정.
-- 왜 REPLICATION SLAVE, REPLICATION CLIENT가 필요한가:
--   Debezium은 binlog를 읽기 위해 MySQL replica처럼 동작한다. 일반 SELECT 권한으로는 binlog 접근 불가.
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
GRANT LOCK TABLES ON shopping.* TO 'debezium'@'%';
FLUSH PRIVILEGES;

-- 브랜드 마스터
CREATE TABLE IF NOT EXISTS brands (
  brand_id    VARCHAR(16)  NOT NULL,
  brand_name  VARCHAR(128) NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (brand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 카테고리 트리.
-- leaf_path는 "패션/신발/러닝화" 형태의 전체 경로를 중복 저장 — 조회 편의상 비정규화.
CREATE TABLE IF NOT EXISTS categories (
  category_id    VARCHAR(16)  NOT NULL,
  leaf_name      VARCHAR(64)  NOT NULL,
  leaf_path      VARCHAR(256) NOT NULL,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 상품.
-- ES 스키마와 다르게 여기선 options/tags를 JSON 컬럼으로 둔다.
-- Enricher 단계에서 JSON 파싱해 ES의 nested options, keyword tags 로 변환.
CREATE TABLE IF NOT EXISTS products (
  product_id      VARCHAR(16)  NOT NULL,
  title           VARCHAR(256) NOT NULL,
  brand_id        VARCHAR(16)  NOT NULL,
  category_id     VARCHAR(16)  NOT NULL,
  price           INT          NOT NULL,
  original_price  INT          NOT NULL,
  stock           INT          NOT NULL DEFAULT 0,
  sales_count     BIGINT       NOT NULL DEFAULT 0,
  view_count      BIGINT       NOT NULL DEFAULT 0,
  review_count    INT          NOT NULL DEFAULT 0,
  review_score    DECIMAL(3,2) NOT NULL DEFAULT 0.00,
  tags_json       JSON         NULL,
  options_json    JSON         NULL,
  seller_id       VARCHAR(16)  NOT NULL,
  shipping_free   TINYINT(1)   NOT NULL DEFAULT 0,
  image_url       VARCHAR(512) NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (product_id),
  KEY idx_brand (brand_id),
  KEY idx_category (category_id),
  KEY idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 시드 데이터 — Phase 3 초기 동작 확인용 최소 셋.
-- 대량 데이터는 scripts/seed-mysql.sh가 dummy/generate-products.py 결과를 LOAD DATA로 주입한다.

INSERT INTO brands (brand_id, brand_name) VALUES
  ('B0001', '나이키'),
  ('B0002', '아디다스'),
  ('B0003', '뉴발란스'),
  ('B0013', '유니클로');

INSERT INTO categories (category_id, leaf_name, leaf_path) VALUES
  ('C0001', '러닝화',   '패션/신발/러닝화'),
  ('C0002', '스니커즈', '패션/신발/스니커즈'),
  ('C0010', '티셔츠',   '패션/의류/상의/티셔츠');

INSERT INTO products (product_id, title, brand_id, category_id, price, original_price, stock,
                      sales_count, view_count, review_count, review_score,
                      tags_json, options_json, seller_id, shipping_free, image_url)
VALUES
  ('P000000001', '나이키 프리미엄 러닝화 001', 'B0001', 'C0001', 89900, 129900, 120,
    15000, 220000, 1200, 4.45,
    JSON_ARRAY('러닝','운동화','나이키'),
    JSON_ARRAY(JSON_OBJECT('color','블랙','size','270','stock',30),
               JSON_OBJECT('color','화이트','size','260','stock',20)),
    'S0001', 1, 'https://cdn.example.com/p/1.jpg'),
  ('P000000002', '아디다스 베스트 스니커즈 002', 'B0002', 'C0002', 49900, 79900, 80,
    8500, 150000, 900, 4.20,
    JSON_ARRAY('스니커즈','데일리','아디다스'),
    JSON_ARRAY(JSON_OBJECT('color','네이비','size','260','stock',40)),
    'S0002', 0, 'https://cdn.example.com/p/2.jpg'),
  ('P000000003', '유니클로 신상 티셔츠 003', 'B0013', 'C0010', 19900, 29900, 300,
    25000, 400000, 2100, 4.60,
    JSON_ARRAY('티셔츠','반팔','유니클로'),
    JSON_ARRAY(JSON_OBJECT('color','화이트','size','L','stock',100),
               JSON_OBJECT('color','블랙','size','M','stock',80)),
    'S0003', 1, 'https://cdn.example.com/p/3.jpg');
