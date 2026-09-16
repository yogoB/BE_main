#!/usr/bin/env python3
"""수집한 구독 정규화 CSV(5개 카테고리) → 요고비 subscription_service·subscription_tier.

입력은 팀이 공식 페이지에서 확인해 정규화한 `normalized_*.csv` 다(OTT·음악·AI·전자책·클라우드).
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
    python3 scripts/catalog_subscriptions_from_handoff.py <handoff 디렉터리> <출력 디렉터리>
"""
import argparse
import collections
import csv
import os
import re
import sys
from urllib.parse import urlsplit, urlunsplit

SOURCES = [
    ("normalized_ott.csv", "OTT"),
    ("normalized_music.csv", "MUSIC"),
    ("normalized_ai.csv", "AI"),
    ("normalized_ebook.csv", "EBOOK"),
    ("normalized_cloud.csv", "CLOUD"),
]
# 개인이 직접 고를 수 있는 유형만. 나머지는 화면에 올려도 선택할 수 없다.
PERSONAL = {"individual", "family", "student"}

SERVICE_HEADER = ["id", "name", "category", "official_url"]
# currency 는 맨 뒤다 — 기존 열 순서를 바꾸면 COPY(HEADER MATCH)와 합본 CSV 가 함께 깨진다.
TIER_HEADER = ["id", "service_id", "name", "price", "concurrent_streams", "quality", "note", "currency"]
# 저장할 수 있는 통화. 그 외는 표기 방법이 없어 담지 않는다(DB CHECK 와 같은 목록).
CURRENCIES = {"KRW", "USD"}


def source_url(text):
    """발행 규칙(scripts/catalog_csv.py)에 맞춰 query·fragment 없는 HTTPS 로 정규화한다."""
    parts = urlsplit((text or "").strip())
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return None
    return urlunsplit(("https", parts.netloc, parts.path, "", ""))


def read_existing(path, key):
    """현재 시드를 {키: 행} 으로 읽는다. 없으면 빈 dict."""
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8", newline="") as handle:
        return {key(row): row for row in csv.DictReader(handle)}


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
    parser.add_argument("handoff")
    parser.add_argument("target")
    parser.add_argument("--seed", default="db/seed", help="기존 시드 위치(ID 를 물려받는다)")
    args = parser.parse_args()

    services = read_existing(os.path.join(args.seed, "subscription_service.csv"), lambda r: r["name"])
    tiers = read_existing(os.path.join(args.seed, "subscription_tier.csv"),
                          lambda r: (r["service_id"], r["name"]))
    for row in tiers.values():
        row.setdefault("currency", "KRW")
    next_service = max((int(r["id"]) for r in services.values()), default=0) + 1
    next_tier = max((int(r["id"]) for r in tiers.values()), default=0) + 1
    kept_services, kept_tiers = len(services), len(tiers)

    dropped = collections.Counter()
    for filename, category in SOURCES:
        path = os.path.join(args.handoff, filename)
        if not os.path.exists(path):
            dropped[f"파일 없음: {filename}"] += 1
            continue
        with open(path, encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
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
                if key in tiers:
                    dropped["이미 있는 등급(기존 ID 유지)"] += 1
                    continue
                concurrent = streams(row["features"])
                tiers[key] = {
                    "id": str(next_tier), "service_id": service["id"], "name": tier_name,
                    "price": str(int(float(price))),
                    "concurrent_streams": "" if concurrent is None else str(concurrent),
                    "quality": quality(row["features"]) or "",
                    "note": (row["features"] or "").strip()[:200],
                    "currency": row["currency"],
                }
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
