#!/usr/bin/env python3
"""
쇼핑 상품 더미 데이터 생성기.

products-template.json의 스키마(dynamic: strict)에 정확히 일치하는 JSONL을 생성한다.
strict 모드이므로 스키마 외 필드가 섞이면 색인이 실패한다 — 템플릿 수정 시 함께 갱신할 것.

주의: options는 nested 타입이므로 Lucene 내부적으로 hidden doc이 곱해진다.
      상품 1M × 평균 옵션 6개 → Lucene doc ≈ 7M. 데이터 규모 추정 시 이 계수를 반영해야 한다.

사용법:
    python3 generate-products.py --count 1000000 --output dummy/products.jsonl
"""

import argparse
import json
import random
import sys
from datetime import datetime, timedelta, timezone

BRANDS = [
    ("B0001", "나이키"),  ("B0002", "아디다스"),    ("B0003", "뉴발란스"),
    ("B0004", "퓨마"),    ("B0005", "언더아머"),    ("B0006", "아식스"),
    ("B0007", "컨버스"),  ("B0008", "반스"),        ("B0009", "리복"),
    ("B0010", "휠라"),    ("B0011", "데상트"),      ("B0012", "무신사스탠다드"),
    ("B0013", "유니클로"),("B0014", "자라"),        ("B0015", "H&M"),
    ("B0016", "에잇세컨즈"),("B0017", "스파오"),    ("B0018", "탑텐"),
    ("B0019", "지오다노"),("B0020", "캠브리지멤버스"),
]

# 계층형 카테고리. category_path는 누적 배열로 만들어 상위 카테고리 필터도 한 번에 걸리게 한다.
CATEGORY_TREE = [
    ("패션/신발/러닝화",       ["러닝", "운동화", "조깅"]),
    ("패션/신발/스니커즈",     ["스니커즈", "데일리", "캐주얼"]),
    ("패션/신발/구두",         ["구두", "정장", "포멀"]),
    ("패션/의류/상의/티셔츠",  ["티셔츠", "반팔", "데일리"]),
    ("패션/의류/상의/셔츠",    ["셔츠", "정장", "캐주얼"]),
    ("패션/의류/상의/후드",    ["후드", "맨투맨", "스트릿"]),
    ("패션/의류/하의/청바지",  ["청바지", "데님", "와이드"]),
    ("패션/의류/하의/슬랙스",  ["슬랙스", "정장", "오피스"]),
    ("패션/의류/아우터/자켓",  ["자켓", "간절기", "아우터"]),
    ("패션/의류/아우터/패딩",  ["패딩", "겨울", "방한"]),
    ("패션/가방/백팩",         ["백팩", "데일리", "학생"]),
    ("패션/가방/크로스백",     ["크로스백", "숄더", "미니"]),
    ("패션/액세서리/모자",     ["모자", "캡", "버킷"]),
    ("패션/액세서리/양말",     ["양말", "스포츠", "패션"]),
    ("디지털/스마트폰",        ["스마트폰", "모바일"]),
    ("디지털/노트북",          ["노트북", "랩탑"]),
    ("디지털/이어폰",          ["이어폰", "블루투스", "무선"]),
    ("뷰티/스킨케어/토너",     ["토너", "스킨", "보습"]),
    ("뷰티/메이크업/립스틱",   ["립스틱", "틴트", "립"]),
    ("식품/간식/과자",         ["과자", "스낵", "간식"]),
]

COLORS = ["블랙", "화이트", "네이비", "그레이", "베이지", "레드", "블루", "카키", "브라운", "핑크"]
SIZES_FASHION = ["XS", "S", "M", "L", "XL", "XXL"]
SIZES_SHOES = ["230", "240", "250", "260", "270", "280"]
DESCRIPTORS = ["프리미엄", "베스트", "신상", "인기", "한정", "시즌오프", "스테디", "정품"]


def build_category_paths(leaf_path: str) -> list[str]:
    """'패션/신발/러닝화' -> ['패션', '패션/신발', '패션/신발/러닝화']."""
    parts = leaf_path.split("/")
    return ["/".join(parts[: i + 1]) for i in range(len(parts))]


def random_product(idx: int, now: datetime) -> dict:
    brand_id, brand_name = random.choice(BRANDS)
    leaf_path, category_tags = random.choice(CATEGORY_TREE)
    category_path = build_category_paths(leaf_path)
    category_leaf = leaf_path.split("/")[-1]
    descriptor = random.choice(DESCRIPTORS)

    is_shoes = "신발" in leaf_path
    size_pool = SIZES_SHOES if is_shoes else SIZES_FASHION

    title = f"{brand_name} {descriptor} {category_leaf} {random.randint(1, 999):03d}"

    # 실제 쇼핑몰 가격대와 비슷한 분포를 흉내 — 10의 배수 + 900원 단위.
    original_price = random.choice([9900, 19900, 29900, 49900, 79900, 129900, 199900, 299900])
    discount_rate = round(random.choice([0.0, 0.0, 0.1, 0.2, 0.3, 0.5]), 2)
    price = int(original_price * (1 - discount_rate))

    stock = random.randint(0, 500)

    # 3~8개 옵션 조합. (color, size) 중복 제거.
    num_options = random.randint(3, 8)
    options: list[dict] = []
    used: set[tuple[str, str]] = set()
    for _ in range(num_options):
        key = (random.choice(COLORS), random.choice(size_pool))
        if key in used:
            continue
        used.add(key)
        options.append({
            "color": key[0],
            "size": key[1],
            "stock": random.randint(0, 100),
        })

    created_at = now - timedelta(days=random.randint(0, 720))
    updated_at = now - timedelta(days=random.randint(0, 30))

    tags = sorted(set(category_tags + [brand_name, descriptor]))

    return {
        "product_id":     f"P{idx:09d}",
        "title":          title,
        "brand":          brand_name,
        "brand_id":       brand_id,
        "category_path":  category_path,
        "category_leaf":  category_leaf,
        "tags":           tags,
        "price":          price,
        "original_price": original_price,
        "discount_rate":  discount_rate,
        "currency":       "KRW",
        "stock":          stock,
        "in_stock":       stock > 0,
        "sales_count":    random.randint(0, 2_000_000),
        "view_count":     random.randint(0, 10_000_000),
        "review_count":   random.randint(0, 50_000),
        "review_score":   round(random.uniform(2.5, 5.0), 2),
        "options":        options,
        "seller_id":      f"S{random.randint(1, 500):04d}",
        "shipping_free":  random.random() < 0.6,
        "image_url":      f"https://cdn.example.com/p/{idx}.jpg",
        "created_at":     created_at.isoformat(),
        "updated_at":     updated_at.isoformat(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=100_000, help="생성할 상품 수")
    parser.add_argument("--output", type=str, default="dummy/products.jsonl")
    parser.add_argument("--seed", type=int, default=42, help="재현성을 위한 시드")
    args = parser.parse_args()

    random.seed(args.seed)
    now = datetime.now(timezone.utc)

    with open(args.output, "w", encoding="utf-8") as f:
        for i in range(1, args.count + 1):
            doc = random_product(i, now)
            f.write(json.dumps(doc, ensure_ascii=False) + "\n")
            if i % 50_000 == 0:
                print(f"  generated {i:,}/{args.count:,}", file=sys.stderr)

    print(f"done: {args.count:,} docs -> {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
