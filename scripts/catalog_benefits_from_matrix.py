#!/usr/bin/env python3
"""수집한 통신사 요금제 매트릭스 → 요고비 plan_benefit.csv 추출.

매트릭스의 **요금제명 공식 표기**에서만 제휴 혜택을 읽는다. 예: "베스트 Max(넷플릭스)".
스마트초이스 등 제3자 사이트는 쓰지 않는다(저작권 정책상 단체의 복제·배포 불허).

**등급을 아는 행과 모르는 행을 다르게 다룬다 — 이게 이 스크립트의 핵심이다.**
  - "(웨이브 광고형)"처럼 등급까지 표기 → `FREE` + 해당 `tier_id`. 계산에 반영된다.
  - "(웨이브)"처럼 서비스만 표기   → `BUNDLE_INCLUDED` + `tier_id` 비움. **금액 효과 0, 표시만.**
    통신사가 어느 등급을 주는지 모르는데 `FREE`로 넣으면 프리미엄 등급까지 0원이 되어
    실제보다 싸게 추천한다(사용자에게 손해다). 모르면 금액을 만들지 않는다 — 절대 원칙 2·4.

사용:
    python3 scripts/catalog_benefits_from_matrix.py <매트릭스.csv> <출력.csv>
"""
import argparse
import collections
import csv
import json
import re
import sys
from urllib.parse import urlsplit, urlunsplit

HEADER = ["carrier", "plan_name", "service_id", "tier_id", "benefit_type", "discount_value",
          "is_exclusive", "exclusive_group", "valid_from", "valid_to", "source_url", "collected_at"]

# 공식 표기에 쓰이는 서비스 이름 → db/seed/subscription_service.csv 의 id
SERVICES = {"넷플릭스": 1, "디즈니": 2, "티빙": 3, "웨이브": 4, "왓챠": 5, "유튜브": 6}

# 서비스별 등급 표기 → db/seed/subscription_tier.csv 의 id. 표기가 없으면 등급 미확인이다.
TIERS = {
    1: {"광고형": 1, "스탠다드": 2, "프리미엄": 3},
    2: {"스탠다드": 4, "프리미엄": 5},
    3: {"광고형": 6, "베이직": 7, "스탠다드": 8, "프리미엄": 9},
    4: {"광고형": 10, "베이직": 11, "스탠다드": 12, "프리미엄": 13},
    5: {"베이직": 14, "프리미엄": 15},
    6: {"라이트": 16},
}

BRACKET = re.compile(r"[(（]([^)）]*)[)）]")


def source_url(text):
    """발행 규칙(scripts/catalog_csv.py)에 맞춰 query·fragment 없는 HTTPS 로 정규화한다."""
    parts = urlsplit((text or "").strip())
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return None
    return urlunsplit(("https", parts.netloc, parts.path, "", ""))


def tier_of(service_id, label):
    """괄호 안 표기에서 등급을 읽는다. 등급 단어가 없으면 None(미확인)."""
    for word, tier_id in TIERS.get(service_id, {}).items():
        if word in label:
            return tier_id
    return None


def benefits_in(label):
    """괄호 안 표기 → [(service_id, tier_id)]. '티빙&웨이브'처럼 여러 개면 모두 돌려준다."""
    found = []
    for name, service_id in SERVICES.items():
        if name in label:
            found.append((service_id, tier_of(service_id, label)))
    return found


def load_details(path):
    """fetch_skt_ott_benefits.py 결과 → {(요금제명, service_id): (benefit_type, discount_value, tier_id)}.

    등급이 확정된 단일 서비스만 쓴다. '티빙&웨이브' 같은 묶음 상품은 우리 카탈로그에 해당 상품이 없어
    할인액을 한쪽 서비스에 붙이면 과대 할인이 되므로 제외한다.
    """
    if not path:
        return {}
    out = {}
    for record in json.load(open(path, encoding="utf-8")).values():
        tier, service_id = record.get("tier"), record.get("serviceId")
        if not tier or not service_id or tier[0] != service_id:
            continue                                   # 등급 미확정 또는 묶음 상품
        if "&" in record["planName"]:
            continue                                   # 티빙&웨이브 등 묶음
        if record.get("kind") == "INCLUDED":
            pays = record.get("customerPays") or 0
            # 부담금 0 = 실제 무료 제공. 부담금이 있으면 그만큼만 내므로 (정가 - 부담금) 할인이다.
            entry = ("FREE", "", tier[1]) if pays == 0 else ("FIXED_DISCOUNT", None, tier[1], pays)
        else:
            amount = record.get("maxDiscount")
            if not amount:
                continue
            entry = ("FIXED_DISCOUNT", str(amount), tier[1])
        out[(record["planName"], service_id)] = entry
    return out


def convert(source, details=None):
    details = details or {}
    tier_price = {1: 7000, 2: 13500, 3: 17000, 4: 9900, 5: 13900, 6: 5500, 7: 9500, 8: 13500,
                  9: 17000, 10: 5500, 11: 7900, 12: 10900, 13: 13900, 14: 7900, 15: 12900,
                  16: 8500, 17: 14900}          # db/seed/subscription_tier.csv 정가 (부담금 → 할인액 환산용)
    dropped = collections.Counter()
    seen = set()
    out = []
    for row in csv.DictReader(source):
        if row["판매상태"].strip() != "가입가능" or row["검증등급"].strip() == "2차자료":
            continue
        carrier = row["브랜드"].strip() or row["통신사구분"].strip()
        plan_name = row["요금제명"].strip()
        url = source_url(row["출처URL"])
        collected = row["확인일"].strip()
        if not (carrier and plan_name and url and collected):
            continue
        for match in BRACKET.finditer(plan_name):
            for service_id, tier_id in benefits_in(match.group(1)):
                # plan_benefit 은 (요금제, 서비스) 단위로 하나만 둔다. 같은 요금제명이 여러 행이면 첫 건만.
                key = (carrier, plan_name, service_id)
                if key in seen:
                    dropped["중복 (요금제·서비스)"] += 1
                    continue
                seen.add(key)
                # 공식 상세를 수집했으면 등급·금액을 확정해 계산에 넣는다.
                detail = details.get((plan_name, service_id))
                if detail:
                    kind, amount, confirmed_tier = detail[0], detail[1], detail[2]
                    if amount is None:                         # 부담금만 아는 경우 정가에서 환산
                        amount = str(max(0, tier_price[confirmed_tier] - detail[3]))
                    tier_id, benefit_type, discount_value = confirmed_tier, kind, amount
                    dropped[f"공식 상세 확정 → {kind}"] += 1
                else:
                    benefit_type = "FREE" if tier_id is not None else "BUNDLE_INCLUDED"
                    discount_value = ""
                    if tier_id is None:
                        dropped["등급 미표기 → BUNDLE_INCLUDED(표시만)"] += 1
                out.append({
                    "carrier": carrier,
                    "plan_name": plan_name,
                    "service_id": str(service_id),
                    # 등급을 알 때만 채운다. 비우면 그 서비스의 모든 등급에 매칭된다(PlanBenefit.matches).
                    "tier_id": "" if tier_id is None else str(tier_id),
                    # 등급을 모르면 금액을 깎지 않는다. BUNDLE_INCLUDED 는 apply()가 정가를 그대로 돌려준다.
                    "benefit_type": benefit_type,
                    "discount_value": discount_value,
                    "is_exclusive": "false",
                    "exclusive_group": "",
                    "valid_from": "",
                    "valid_to": "",
                    "source_url": url,
                    "collected_at": collected,
                })
    return out, dropped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("target")
    parser.add_argument("--details", help="fetch_skt_ott_benefits.py 가 만든 JSON (등급·금액 확정용)")
    args = parser.parse_args()

    details = load_details(args.details)
    with open(args.source, encoding="utf-8-sig", newline="") as source:
        rows, dropped = convert(source, details)
    with open(args.target, "w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=HEADER)
        writer.writeheader()
        writer.writerows(rows)

    counts = collections.Counter(r["benefit_type"] for r in rows)
    print(f"추출 {len(rows)}행 → {args.target}")
    for kind, n in counts.most_common():
        print(f"  {kind}: {n}행" + ("  (표시만, 계산 미반영)" if kind == "BUNDLE_INCLUDED" else "  (계산 반영)"))
    for reason, count in dropped.most_common():
        print(f"  {reason}: {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
