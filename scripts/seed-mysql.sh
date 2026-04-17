#!/usr/bin/env bash
#
# MySQL shopping DB에 대량 상품 데이터 주입.
#
# 흐름:
#   1) dummy/generate-products.py로 JSONL 생성 (없으면)
#   2) JSONL → CSV 변환 (JSON 배열/객체는 문자열로 직렬화)
#   3) LOAD DATA LOCAL INFILE로 bulk insert
#
# 왜 LOAD DATA 인가:
#   INSERT 수백만 건을 하나씩 돌리면 로그/트랜잭션 오버헤드가 심해 PoC 환경에서 수 시간 소요.
#   LOAD DATA는 서버 측에서 일괄 파싱하므로 10배 이상 빠르다.
#
# 주의:
#   - Debezium은 snapshot.mode=initial 이므로 등록 시점에 DB에 있는 데이터를 Kafka로 한 번에 밀어낸다.
#   - 따라서 "커넥터 등록 전에 seed 먼저" 하거나, "등록 후 seed하고 binlog로 흐르게" 둘 중 선택.
#   - PoC에선 전자를 권장 (snapshot으로 한 번에 흐름 확인).

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COUNT="${COUNT:-100000}"
JSONL="${PROJECT_ROOT}/dummy/products.jsonl"
CSV="${PROJECT_ROOT}/dummy/products.csv"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root}"
MYSQL_DB="${MYSQL_DB:-shopping}"

# 1) JSONL 생성 (없으면)
if [[ ! -f "${JSONL}" ]] || [[ "$(wc -l < "${JSONL}" | tr -d ' ')" -lt "${COUNT}" ]]; then
  echo "generating ${COUNT} products -> ${JSONL}"
  python3 "${PROJECT_ROOT}/dummy/generate-products.py" --count "${COUNT}" --output "${JSONL}"
fi

# 2) JSONL -> CSV
# generate-products.py는 ES 스키마 기준이므로 MySQL 컬럼에 맞게 변환한다.
#   brand_id, category_id 매핑: 생성기는 category_path만 주므로 leaf_name으로 역조회 필요.
#   여기선 간단화: brand_name → brand_id 매핑 딕셔너리를 스크립트에 박아둔다.
echo "converting JSONL -> CSV"
python3 - <<PY
import json, csv, sys, hashlib

BRAND_NAME_TO_ID = {
    "나이키":"B0001","아디다스":"B0002","뉴발란스":"B0003","퓨마":"B0004","언더아머":"B0005",
    "아식스":"B0006","컨버스":"B0007","반스":"B0008","리복":"B0009","휠라":"B0010",
    "데상트":"B0011","무신사스탠다드":"B0012","유니클로":"B0013","자라":"B0014","H&M":"B0015",
    "에잇세컨즈":"B0016","스파오":"B0017","탑텐":"B0018","지오다노":"B0019","캠브리지멤버스":"B0020",
}

def cat_id(leaf_path: str) -> str:
    # category 마스터 테이블과 매칭하려면 id가 안정적이어야 하므로 path hash 앞 4byte 사용
    h = hashlib.md5(leaf_path.encode("utf-8")).hexdigest()[:4].upper()
    return f"C{h}"

with open("${JSONL}") as fin, open("${CSV}", "w", newline="") as fout:
    w = csv.writer(fout, quoting=csv.QUOTE_ALL, escapechar="\\\\")
    for line in fin:
        d = json.loads(line)
        w.writerow([
            d["product_id"],
            d["title"],
            BRAND_NAME_TO_ID.get(d["brand"], "B0001"),
            cat_id("/".join(d["category_path"])),
            d["price"],
            d["original_price"],
            d["stock"],
            d["sales_count"],
            d["view_count"],
            d["review_count"],
            d["review_score"],
            json.dumps(d["tags"], ensure_ascii=False),
            json.dumps(d["options"], ensure_ascii=False),
            d["seller_id"],
            1 if d["shipping_free"] else 0,
            d["image_url"],
            d["created_at"].replace("T"," ").split("+")[0].split(".")[0],
            d["updated_at"].replace("T"," ").split("+")[0].split(".")[0],
        ])
PY

# 3) categories 테이블 동기화 (seed data에서 덮어쓰기)
echo "syncing categories table from generated data"
python3 - <<PY
import json, hashlib, subprocess

seen = {}
with open("${JSONL}") as f:
    for line in f:
        d = json.loads(line)
        leaf_path = "/".join(d["category_path"])
        leaf_name = d["category_leaf"]
        h = hashlib.md5(leaf_path.encode("utf-8")).hexdigest()[:4].upper()
        cid = f"C{h}"
        seen[cid] = (leaf_name, leaf_path)

sql = "INSERT IGNORE INTO categories (category_id, leaf_name, leaf_path) VALUES\n"
rows = [f"('{cid}', '{name}', '{path}')" for cid, (name, path) in seen.items()]
sql += ",\n".join(rows) + ";"

subprocess.run(
    ["mysql", "-h${MYSQL_HOST}", "-P${MYSQL_PORT}", "-u${MYSQL_USER}", "-p${MYSQL_PASS}", "${MYSQL_DB}"],
    input=sql.encode("utf-8"), check=True,
)
PY

# 4) LOAD DATA
echo "loading ${CSV} -> shopping.products"
mysql --local-infile=1 -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -u"${MYSQL_USER}" -p"${MYSQL_PASS}" "${MYSQL_DB}" <<SQL
SET SESSION sql_mode = '';
LOAD DATA LOCAL INFILE '${CSV}'
  REPLACE INTO TABLE products
  FIELDS TERMINATED BY ',' ENCLOSED BY '"' ESCAPED BY '\\\\'
  LINES TERMINATED BY '\n'
  (product_id, title, brand_id, category_id, price, original_price, stock,
   sales_count, view_count, review_count, review_score,
   tags_json, options_json, seller_id, shipping_free, image_url,
   created_at, updated_at);

SELECT COUNT(*) AS total_products FROM products;
SQL

echo "done"
