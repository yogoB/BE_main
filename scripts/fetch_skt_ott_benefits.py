#!/usr/bin/env python3
"""SKT 공식 요금제 안내(T world)에서 OTT 혜택의 **제공 등급과 할인 금액**을 1회 수집한다.

요금제명 괄호 표기("베스트 109(넷플릭스)")만으로는 등급도 금액도 알 수 없어
`catalog_benefits_from_matrix.py`가 BUNDLE_INCLUDED(표시만)로 남긴 행을 확정하기 위한 도구다.

근거는 상품 상세의 "구독 혜택 상세" 표다:
    요금제 | T 우주 구독 상품 | 기본 혜택(OTT 1) | 추가 혜택(OTT 2) | 최대 할인 금액

**런타임 호출 금지**(D-05). 오프라인에서 1회 돌려 CSV 를 만들고, 서비스는 그 CSV 만 읽는다.
요청 간 1초 이상 간격을 둔다(docs/data.md §3 수집 준수사항).

사용:
    python3 scripts/fetch_skt_ott_benefits.py <매트릭스.csv> <출력.json>
"""
import argparse
import csv
import html
import json
import re
import sys
import time
import urllib.request

LEDGER = "https://www.tworld.co.kr/core-product/v1/ledger/{}/contents"
AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/140.0 Safari/537.36")
DELAY_SECONDS = 1.2

# 표의 등급 표기 → db/seed/subscription_tier.csv 의 (service_id, tier_id)
TIER_BY_LABEL = [
    ("넷플릭스 광고형 스탠다드", 1, 1), ("넷플릭스 스탠다드", 1, 2), ("넷플릭스 프리미엄", 1, 3),
    ("디즈니+ 스탠다드", 2, 4), ("디즈니+ 프리미엄", 2, 5),
    ("티빙 광고형 스탠다드", 3, 6), ("티빙 베이직", 3, 7), ("티빙 스탠다드", 3, 8), ("티빙 프리미엄", 3, 9),
    ("웨이브 광고형", 4, 10), ("웨이브 베이직", 4, 11), ("웨이브 스탠다드", 4, 12), ("웨이브 프리미엄", 4, 13),
    ("왓챠 베이직", 5, 14), ("왓챠 프리미엄", 5, 15),
    ("유튜브 프리미엄 라이트", 6, 16), ("유튜브 프리미엄", 6, 17),
]


def fetch(product_id):
    request = urllib.request.Request(
        LEDGER.format(product_id),
        headers={"User-Agent": AGENT,
                 "Referer": f"https://www.tworld.co.kr/web/product/callplan/{product_id}"})
    with urllib.request.urlopen(request, timeout=25) as response:
        return json.loads(response.read().decode("utf-8"))


def tables(payload):
    """응답 안의 HTML 표들을 [[셀,...],...] 로 돌려준다."""
    text = html.unescape(json.dumps(payload, ensure_ascii=False)
                         .replace("\\r", " ").replace("\\n", " ").replace("\\t", " "))
    out = []
    for block in re.findall(r"<table.*?</table>", text, re.S):
        rows = []
        for row in re.findall(r"<tr.*?</tr>", block, re.S):
            cells = [re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", c)).strip()
                     for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", row, re.S)]
            if cells:
                rows.append(cells)
        if rows:
            out.append(rows)
    return out


SERVICE_BY_NAME = {"넷플릭스": 1, "디즈니+": 2, "디즈니": 2, "티빙": 3, "웨이브": 4, "왓챠": 5, "유튜브": 6}


def service_of(plan_name):
    """요금제명 괄호에서 서비스를 읽는다. 둘 이상이면(티빙&웨이브) 묶음이라 확정하지 않는다."""
    inner = re.search(r"[(（]([^)）]*)[)）]", plan_name)
    if not inner:
        return None
    hits = {sid for name, sid in SERVICE_BY_NAME.items() if name in inner.group(1)}
    return hits.pop() if len(hits) == 1 else None


def included_row(payload, plan_name):
    """다이렉트 계열 표: 요금제 | 기본제공 멤버십 | 고객 부담금 | 최대 할인.

    '부담금 없음'이면 실제로 무료 제공이다(할인이 아니라 포함).
    """
    compact = plan_name.replace(" ", "")
    for rows in tables(payload):
        header = " ".join(rows[0])
        if "기본제공" not in header or "부담금" not in header:
            continue
        for cells in rows[1:]:
            if cells[0].replace(" ", "") != compact or len(cells) < 3:
                continue
            grade, charge = cells[1], cells[2]
            paid = re.search(r"([\d,]+)\s*원", charge)
            return {"grade": grade, "charge": charge,
                    "customerPays": 0 if not paid else int(paid.group(1).replace(",", ""))}
    return None


def benefit_row(payload, plan_name):
    """'최대 할인 금액' 표에서 이 요금제의 행을 찾는다. 표는 rowspan 때문에 셀 수가 들쭉날쭉하다."""
    compact = plan_name.replace(" ", "")
    for rows in tables(payload):
        header = " ".join(rows[0])
        if "최대 할인 금액" not in header:
            continue
        basic, extra = "", ""
        for cells in rows[1:]:
            # rowspan 으로 앞 칸이 생략되면 인덱스가 밀린다. 금액은 항상 맨 뒤이므로 뒤에서 센다.
            # 5칸 [요금제, T우주, 기본, 추가, 금액] · 4칸 [요금제, 기본, 추가, 금액] · 2칸 [요금제, 금액]
            if len(cells) >= 4:
                basic, extra = cells[-3], cells[-2]
            if cells[0].replace(" ", "") == compact:
                amount = re.search(r"([\d,]+)\s*원", cells[-1])
                return {"basic": basic, "extra": extra,
                        "maxDiscount": int(amount.group(1).replace(",", "")) if amount else None}
    return None


def resolve_tier(label):
    """'넷플릭스 스탠다드를 월 1,000원부터 이용 가능' → (service_id, tier_id).

    '또는'으로 여러 등급을 제시하면 사용자가 고르는 것이므로 확정하지 않는다(None).
    """
    if not label or "또는" in label:
        return None
    hits = [(sid, tid) for name, sid, tid in TIER_BY_LABEL if name in label]
    return hits[0] if len(hits) == 1 else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("matrix")
    parser.add_argument("target")
    args = parser.parse_args()

    wanted = {}
    with open(args.matrix, encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if row["브랜드"].strip() != "SKT" or row["판매상태"].strip() != "가입가능":
                continue
            name, product_id = row["요금제명"].strip(), row["공식상품ID"].strip()
            if product_id and re.search(r"[(（][^)）]*(넷플릭스|디즈니|티빙|웨이브|유튜브)", name):
                wanted[product_id] = name

    results = {}
    for index, (product_id, name) in enumerate(sorted(wanted.items(), key=lambda kv: kv[1])):
        if index:
            time.sleep(DELAY_SECONDS)          # 공식 안내 페이지에 부담을 주지 않는다
        try:
            payload = fetch(product_id)
        except Exception as error:             # noqa: BLE001 — 한 건 실패로 전체를 멈추지 않는다
            print(f"  실패 {name}: {error}", file=sys.stderr)
            continue
        service_id = service_of(name)
        record = {"planName": name, "productId": product_id, "serviceId": service_id}

        included = included_row(payload, name)
        if included:
            # 등급 라벨에 서비스명이 없다("스탠다드"). 괄호의 서비스와 합쳐 해석한다.
            label = f"{[k for k, v in SERVICE_BY_NAME.items() if v == service_id][0]} {included['grade']}" \
                if service_id else included["grade"]
            record.update(included, kind="INCLUDED", tier=resolve_tier(label))
        else:
            discounted = benefit_row(payload, name)
            if not discounted:
                print(f"  표 없음 {name}", file=sys.stderr)
                continue
            record.update(discounted, kind="DISCOUNT", tier=resolve_tier(discounted["basic"]))

        results[name] = record
        mark = ("포함·부담금 " + str(record["customerPays"]) + "원") if record["kind"] == "INCLUDED" \
            else ("할인 " + str(record["maxDiscount"]) + "원")
        print(f"  {name:26} {mark:18} 등급 {record['tier'] or '미확정'}")

    with open(args.target, "w", encoding="utf-8") as target:
        json.dump(results, target, ensure_ascii=False, indent=2)
    confirmed = sum(1 for r in results.values() if r["tier"])
    print(f"\n수집 {len(results)}건 → {args.target} (등급·금액 확정 {confirmed}건)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
