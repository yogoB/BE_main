#!/usr/bin/env python3
"""수집한 통신사 요금제 매트릭스 → 요고비 mobile_plan.csv 변환.

팀이 공식 사이트에서 확인해 만든 매트릭스(35개 열)를 카탈로그 CSV(12개 열)로 옮긴다.
**값을 지어내지 않는다** — 매핑할 수 없는 행은 사유와 함께 제외하고 개수를 보고한다.
검수·발행은 scripts/catalog_csv.py(prepare → 운영자 승인 → publish)가 한다. 이 스크립트는 변환만 한다.

사용:
    python3 scripts/catalog_matrix_to_csv.py <매트릭스.csv> <출력.csv> [--include-conditional]
"""
import argparse
import io
import collections
import csv
import csv_sections
import re
import sys
from urllib.parse import urlsplit, urlunsplit

HEADER = ["carrier", "plan_name", "network_type", "base_price", "data_mb", "voice_min", "sms_cnt",
          "contract_discount_12m", "contract_discount_24m", "age_limit", "source_url", "collected_at"]

# 통신규격 → CSV 망 값. 로더가 5G→FIVE_G, 3G→THREE_G 로 바꾼다.
# '5G/LTE' 통합 상품은 5G 가입 기준이라 5G 로 넣는다(2026-09-16 결정). 단일 값 스키마의 알려진 한계다.
NETWORK = {"5G": "5G", "LTE": "LTE", "3G": "3G", "5G/LTE": "5G"}
UNLIMITED = 999999          # 시드 관례: 무제한
GB_TO_MB = 1024


def amount(text, unit):
    """'100분'·'300건' → 정수. '무제한' → 999999. 수량을 알 수 없으면 None(빈칸으로 나간다)."""
    value = (text or "").strip()
    if not value or value == "미제공":
        return 0 if value == "미제공" else None
    if "무제한" in value:
        return UNLIMITED
    found = re.search(r"(\d[\d,]*)\s*" + unit, value)
    return int(found.group(1).replace(",", "")) if found else None


def data_mb(row):
    """데이터GB → MB. '무제한' → 999999. 빈칸(미확인)이면 None — 이 행은 제외한다(추천 후보 선별에 쓰인다)."""
    value = row["데이터GB"].strip()
    if not value:
        return None
    if "무제한" in value:
        return UNLIMITED
    try:
        return int(round(float(value.replace(",", "")) * GB_TO_MB))
    except ValueError:
        return None


def source_url(text):
    """발행 규칙(scripts/catalog_csv.py)의 출처 형식으로 정규화한다.

    인증정보·query·fragment 없는 HTTPS 주소만 허용하므로 query/fragment 는 떼어낸다
    (docs/catalog-data.md: "필요한 상품 식별 정보는 검수 사유에도 남긴다" — 매트릭스의 공식상품ID가 그 역할).
    https 가 아니거나 호스트가 없으면 None — 그 행은 제외한다.
    """
    parts = urlsplit((text or "").strip())
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return None
    return urlunsplit(("https", parts.netloc, parts.path, "", ""))


def age_limit(row):
    """가입대상·연령조건을 한 칸으로. 둘 다 비면 ALL."""
    parts = [row["가입대상"].strip(), row["연령조건"].strip()]
    joined = " ".join(p for p in parts if p and p != "일반")
    return joined[:100] if joined else "ALL"


def convert(source, include_conditional):
    dropped = collections.Counter()
    best = {}
    for row in csv.DictReader(source):
        if row["판매상태"].strip() != "가입가능":
            dropped["판매중 아님"] += 1
            continue
        if row["검증등급"].strip() == "2차자료":
            dropped["2차자료(가격 신뢰 금지)"] += 1
            continue
        if row["추천판정"].strip() == "추천 제외":
            dropped["추천 제외"] += 1
            continue
        if not include_conditional and row["추천판정"].strip() != "추천 가능":
            dropped["조건부 추천(이번 범위 밖)"] += 1
            continue
        network = NETWORK.get(row["통신규격"].strip())
        if network is None:
            dropped[f"망 매핑 불가({row['통신규격'].strip()})"] += 1
            continue
        price = row["월정액(원)"].strip().replace(",", "")
        if not price.isdigit() or int(price) <= 0:
            dropped["월정액 미확인"] += 1
            continue
        megabytes = data_mb(row)
        if megabytes is None:
            dropped["데이터량 미확인"] += 1
            continue
        carrier = row["브랜드"].strip() or row["통신사구분"].strip()
        name = row["요금제명"].strip()
        if not carrier or not name:
            dropped["통신사·요금제명 없음"] += 1
            continue
        url = source_url(row["출처URL"])
        if url is None:
            dropped["출처 URL 없음/비HTTPS"] += 1
            continue

        key = (carrier, name)
        # 같은 (통신사, 요금제명)은 하나만 남긴다 — DB 자연키이고, 한 문장 upsert 에서 중복은 오류다.
        current = (0 if row["중복대표"].strip() == "Y" else 1,
                   0 if row["검증등급"].strip() == "공식확인" else 1,
                   0 if row["추천판정"].strip() == "추천 가능" else 1,
                   [-ord(c) for c in row["확인일"].strip()],   # 확인일 최신 우선
                   int(row["행번호"]))
        if key in best:
            dropped["중복 키(후순위)"] += 1
            if best[key][0] <= current:
                continue
        voice = amount(row["통화"], "분")
        sms = amount(row["문자"], "건")
        best[key] = (current, {
            "carrier": carrier,
            "plan_name": name,
            "network_type": network,
            "base_price": price,
            "data_mb": str(megabytes),
            # 수량이 없는 공식 표기("기본제공" 등)는 비운다 = 미확인. 0(미제공)과 구분한다(V11).
            "voice_min": "" if voice is None else str(voice),
            "sms_cnt": "" if sms is None else str(sms),
            # 약정 할인액은 매트릭스에 금액이 없다("선택약정 가능"은 조건이지 금액이 아니다). 비워 둔다.
            "contract_discount_12m": "",
            "contract_discount_24m": "",
            "age_limit": age_limit(row),
            "source_url": url,
            "collected_at": row["확인일"].strip(),
        })
    return [value for _, value in best.values()], dropped


def open_source(path, section):
    """단일 CSV 파일도, 합본(#@ 섹션) 파일도 받는다 — 원자료가 한 파일로 모였기 때문이다."""
    text = open(path, encoding="utf-8-sig", newline="").read()
    if not text.lstrip().startswith(("#@", "#")):
        return io.StringIO(text)
    return io.StringIO(csv_sections.split(text)[section])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", help="수집 원자료. 합본(#@ 섹션) 파일이면 --section 을 읽는다")
    parser.add_argument("target")
    parser.add_argument("--section", default="mobile_plan_matrix",
                        help="합본 원자료에서 읽을 섹션 이름 (단일 CSV 를 주면 무시된다)")
    parser.add_argument("--include-conditional", action="store_true",
                        help='추천판정이 "조건부 추천"인 행도 포함한다')
    args = parser.parse_args()

    rows, dropped = convert(open_source(args.source, args.section), args.include_conditional)
    with open(args.target, "w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=HEADER)
        writer.writeheader()
        writer.writerows(rows)

    print(f"변환 {len(rows)}행 → {args.target}")
    unknown = sum(1 for r in rows if not r["voice_min"] or not r["sms_cnt"])
    print(f"통화·문자 미확인 포함: {unknown}행 (빈칸 = 미확인, 0 = 미제공)")
    for reason, count in dropped.most_common():
        print(f"  제외 {count:5d}  {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
