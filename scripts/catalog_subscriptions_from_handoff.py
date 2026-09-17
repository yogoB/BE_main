#!/usr/bin/env python3
"""수집 원자료의 구독 표 → 요고비 subscription_service·subscription_tier.

입력은 합본 원자료(`sources/catalog_sources.csv`)의 `subscription_plans` 섹션이다 —
팀이 공식 페이지에서 확인해 정규화한 5개 카테고리(OTT·음악·AI·전자책·클라우드)가 `구분` 열로 들어 있다.
**기존 ID 를 절대 바꾸지 않는다** — 회원의 `user_subscription.tier_id` 가 그 값을 참조한다.
기존 서비스·티어는 이름으로 찾아 ID 를 물려주고, 새 것만 뒤에 이어 붙인다.

통화는 표기 그대로 싣는다(사용자 결정 2026-09-16). 원화 환산은 BE 가 하루 1회 환율로 **표시만** 하고
계산에는 쓰지 않는다 — 사용자가 실제 결제액을 확인해 넣어야 계산에 들어간다(D-17).

넣지 않는 것(값을 지어내지 않기 위해):
  - KRW·USD 가 아닌 통화. 환산하지 않으므로 표기할 수 없는 통화는 담지 않는다.
  - 가격이 비었거나 0원인 행. 0원은 추천 계산에서 항상 유리해 결과를 왜곡한다.
  - 개인 소비자가 고를 수 없는 유형(team·enterprise·addon·bundle·prepaid·metered 등).
  - 판매 목록에 없는 행(availability != listed).

사용:
    python3 scripts/catalog_subscriptions_from_handoff.py <합본 원자료 CSV> <출력 디렉터리>
"""
import argparse
import collections
import csv
import csv_sections
import os
import re
import sys
from urllib.parse import urlsplit, urlunsplit

# 원자료의 `구분` → 우리 카테고리. **순서가 곧 ID 부여 순서**라 바꾸면 기존 등급 ID 가 밀린다.
CATEGORIES = [("OTT", "OTT"), ("음악", "MUSIC"), ("AI", "AI"), ("전자책", "EBOOK"), ("클라우드", "CLOUD"),
              # 새 카테고리는 **맨 뒤에** 붙인다 — 앞에 끼우면 기존 등급 ID 가 밀린다.
              ("이모티콘", "EMOTICON")]
# 개인이 직접 고를 수 있는 유형만. 나머지는 화면에 올려도 선택할 수 없다.
PERSONAL = {"individual", "family", "student"}

SERVICE_HEADER = ["id", "name", "category", "official_url"]
# 새 열은 맨 뒤에 붙인다 — 기존 열 순서를 바꾸면 COPY(HEADER MATCH)와 합본 CSV 가 함께 깨진다.
TIER_HEADER = ["id", "service_id", "name", "price", "concurrent_streams", "quality", "note",
               "currency", "tax_included"]
# 저장할 수 있는 통화. 그 외는 표기 방법이 없어 담지 않는다(DB CHECK 와 같은 목록).
CURRENCIES = {"KRW", "USD"}


def source_url(text):
    """발행 규칙(scripts/catalog_csv.py)에 맞춰 query·fragment 없는 HTTPS 로 정규화한다."""
    parts = urlsplit((text or "").strip())
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return None
    return urlunsplit(("https", parts.netloc, parts.path, "", ""))


def read_existing(path, dataset, key):
    """현재 시드(합본 CSV의 한 섹션)를 {키: 행} 으로 읽는다. 없으면 빈 dict.

    **비어 있으면 ID 를 새로 매긴다** — 회원의 user_subscription.tier_id 가 깨지므로
    합본 경로가 틀렸는데 조용히 넘어가지 않게 호출부가 결과 건수를 보고한다.
    """
    if not os.path.exists(path):
        return {}
    return {key(row): row for row in csv_sections.read(path, dataset)}


def streams(features):
    """'동시 2대' 같은 표기에서 동시 시청 수를 읽는다. 없으면 None(미확인)."""
    found = re.search(r"동시\s*(\d+)\s*대", features or "")
    return int(found.group(1)) if found else None


def quality(features):
    """화질 표기만 뽑는다. 여러 개면 첫 번째."""
    found = re.search(r"\b(4K\+HDR|4K|UHD|FHD|HD|SD|무손실|고음질)\b", features or "")
    return found.group(1) if found else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", help="합본 원자료 CSV (#@ subscription_plans 섹션)")
    parser.add_argument("target")
    parser.add_argument("--seed", default="db/seed/catalog_combined.csv",
                        help="기존 시드 합본(ID 를 물려받는다). 카탈로그 원본은 이 파일 하나다")
    args = parser.parse_args()

    services = read_existing(args.seed, "subscription_service", lambda r: r["name"])
    tiers = read_existing(args.seed, "subscription_tier", lambda r: (r["service_id"], r["name"]))
    for row in tiers.values():
        row.setdefault("currency", "KRW")
        row.setdefault("tax_included", "true" if row.get("currency", "KRW") == "KRW" else "false")
    next_service = max((int(r["id"]) for r in services.values()), default=0) + 1
    next_tier = max((int(r["id"]) for r in tiers.values()), default=0) + 1
    kept_services, kept_tiers = len(services), len(tiers)

    dropped = collections.Counter()
    written = set()                     # 이번 실행에서 이미 채운 (서비스, 등급명)
    by_category = collections.defaultdict(list)
    for row in csv_sections.read(args.source, "subscription_plans"):
        by_category[row["구분"]].append(row)
    for source_category, category in CATEGORIES:
        rows = by_category.get(source_category)
        if not rows:
            dropped[f"원자료에 '{source_category}' 행 없음"] += 1
            continue
        for row in rows:
            if row["currency"] not in CURRENCIES:
                dropped[f"지원하지 않는 통화({row['currency']})"] += 1
                continue
            if row["availability"] != "listed":
                dropped["판매 목록에 없음"] += 1
                continue
            if row["plan_type"] not in PERSONAL:
                dropped[f"개인이 고를 수 없는 유형({row['plan_type']})"] += 1
                continue
            price = row["regular_price"].strip()
            if not price or float(price) <= 0:
                dropped["가격 없음 또는 0원"] += 1
                continue
            url = source_url(row["source_url"])
            if url is None:
                dropped["출처 URL 없음/비HTTPS"] += 1
                continue

            name = row["service"].strip()
            service = services.get(name)
            if service is None:
                # 기존에 없던 서비스만 새 ID 를 받는다.
                service = {"id": str(next_service), "name": name,
                           "category": category, "official_url": url}
                services[name] = service
                next_service += 1

            tier_name = row["plan_name"].strip()
            key = (service["id"], tier_name)
            # 같은 이름이 여러 번 나온다(월간·연간). **첫 행만 쓴다** — 우리 price 는 월 요금이고
            # 원자료가 월간을 먼저 싣는다. 이 규칙을 빼면 연간 금액이 월 요금 자리에 들어간다(실제로 그랬다).
            if key in written:
                dropped["같은 이름의 다른 청구주기(첫 행만 채택)"] += 1
                continue
            written.add(key)
            # 이미 있는 등급은 **ID 만 물려받고 값은 원자료로 갱신한다**.
            # 건너뛰기만 하면 원자료의 가격·통화 수정이 영영 반영되지 않는다(실제로 그랬다).
            existing = tiers.get(key)
            if existing is not None:
                dropped["기존 등급 갱신(ID 유지)"] += 1
            concurrent = streams(row["features"])
            # 기존 등급은 **금액·통화·세금만** 원자료로 갱신한다. 설명·화질·동시접속은 손으로 다듬은 값이
            # 있을 수 있어 그대로 둔다 — 가격 수정 한 번에 화면 문구가 통째로 흔들리지 않게 한다.
            tiers[key] = {
                "id": existing["id"] if existing else str(next_tier),
                "service_id": service["id"], "name": tier_name,
                "price": str(int(float(price))),
                "concurrent_streams": existing["concurrent_streams"] if existing
                    else ("" if concurrent is None else str(concurrent)),
                "quality": existing["quality"] if existing else (quality(row["features"]) or ""),
                "note": existing["note"] if existing else (row["features"] or "").strip()[:200],
                "currency": row["currency"],
                # 표기가에 세금이 포함됐는지. 국내 표시가는 총액(부가세 포함)이 관행이고,
                # 해외 사업자의 외화 표기가는 세금 별도라 한국 이용자에겐 결제 시 10%가 더 붙는다.
                # 원자료에 세금 칸이 없어 통화로 가른다 — 틀린 행은 이 열만 고치면 된다(가격은 출처 그대로다).
                "tax_included": "true" if row["currency"] == "KRW" else "false",
            }
            if existing is None:
                next_tier += 1

    os.makedirs(args.target, exist_ok=True)
    with open(os.path.join(args.target, "subscription_service.csv"), "w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=SERVICE_HEADER)
        writer.writeheader()
        writer.writerows(sorted(services.values(), key=lambda r: int(r["id"])))
    with open(os.path.join(args.target, "subscription_tier.csv"), "w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=TIER_HEADER)
        writer.writeheader()
        writer.writerows(sorted(tiers.values(), key=lambda r: int(r["id"])))

    print(f"서비스 {kept_services} → {len(services)}개, 등급 {kept_tiers} → {len(tiers)}개")
    for reason, count in dropped.most_common():
        print(f"  제외 {count:4d}  {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
