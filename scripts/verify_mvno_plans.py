#!/usr/bin/env python3
r"""승인된 카탈로그의 요금제 가격을 공식 사이트에서 대조하고 판정을 보고한다.

**검증 전용이다. CSV도 catalog-store도 고치지 않는다.** 카탈로그는 초기 데이터셋이 기준이고,
이후 변경은 검수를 거쳐 들어온다(D-29). 이 스크립트는 그 검수의 한 소스이며,
스마트초이스(`SmartChoicePriceOracle`)·AI 서버(`POST /catalog/candidates`)와 나란히 쓴다.
공개 JSON 엔드포인트만 읽으므로 **LLM 비용이 없다**.

판정은 D-29 규칙을 그대로 따른다:

    VERIFIED    공식 목록이 같은 금액을 확인했다 (100원 이내 차이는 같은 값 — 표기 반올림)
    MISMATCH    공식 목록이 다른 금액을 보고했다 → 사람이 확인해야 한다
    UNVERIFIED  공식 목록에 없다. "틀렸다"가 아니라 "확인 못 했다"는 뜻이라 막지 않는다

**런타임 호출 금지**(D-05). 하루 1회 오프라인 배치이며 서비스는 발행된 CSV만 읽는다.
요청 간 1초 이상 간격을 둔다(docs/data.md §3).

    python3 scripts/verify_mvno_plans.py --store ../catalog-store
    python3 scripts/verify_mvno_plans.py --store ../catalog-store --carrier sk7mobile --report /tmp/r.csv

하루 1회 실행 예(crontab -e):

    5 4 * * * cd /경로/yogoB/BE_main && python3 scripts/verify_mvno_plans.py \
      --store ../catalog-store --report /tmp/plan-verify-$(date +\%F).csv
"""
import argparse
import collections
import csv
import re
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

AGENT = "Mozilla/5.0 (compatible; yogobi-catalog/1.0; +offline batch, 1 req/s)"
DELAY_SECONDS = 1.0
TOLERANCE_WON = 100      # D-29: 100원 이내 차이는 같은 값으로 본다(표기 반올림 오탐 방지)
REPORT_FIELDS = ["판정", "carrier", "plan_name", "카탈로그가격", "공식가격", "차액", "source_url"]


def post_json(url, payload):
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest",
                 "User-Agent": AGENT},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8", "replace"))


def to_int(value, default=""):
    digits = "".join(character for character in str(value or "") if character.isdigit())
    return int(digits) if digits else default


def collect_sk7mobile():
    """SK세븐모바일. 목록 화면이 호출하는 공개 JSON 엔드포인트를 그대로 쓴다."""
    source = "https://www.sk7mobile.com/prod/data/callingPlanList.do"
    payload = post_json("https://www.sk7mobile.com/prod/data/searchPlanList.do", {})
    for item in payload.get("resultList") or []:
        name = (item.get("prodNm") or "").strip()
        # basicAmt는 정가, basicAmt2는 실제 판매가다. 요고비는 실제 지불 금액을 다루므로
        # 판매가를 쓴다. 판매가 표기가 없을 때만 정가로 떨어진다.
        price = to_int(item.get("basicAmt2"), default=None)
        if price is None:
            price = to_int(item.get("basicAmt"), default=None)
        if not name or price is None:
            continue
        yield {"carrier": "SK세븐모바일", "plan_name": name,
               "base_price": price, "source_url": source}


ADAPTERS = {"sk7mobile": collect_sk7mobile}


def read_plans(store):
    """`current`가 가리키는 승인 리비전. 승인되지 않은 데이터는 검증 대상이 아니다."""
    revision = (store / "current").read_text(encoding="utf-8").strip()
    path = store / "revisions" / revision / "mobile_plan.csv"
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return revision, list(csv.DictReader(handle))


def judge(catalog, collected):
    """(carrier, plan_name)으로 맞춘다. 발행 도구가 쓰는 키와 같다."""
    official = {(row["carrier"], row["plan_name"]): row for row in collected}
    carriers = {row["carrier"] for row in collected}
    verdicts = []
    for row in catalog:
        key = (row["carrier"], row["plan_name"])
        # 어댑터가 없는 사업자는 판정 대상이 아니다. 확인 못 한 것과 구분한다.
        if row["carrier"] not in carriers:
            continue
        found = official.get(key)
        if found is None:
            verdicts.append({"판정": "UNVERIFIED", "carrier": key[0], "plan_name": key[1],
                             "카탈로그가격": row["base_price"], "공식가격": "", "차액": "",
                             "source_url": row["source_url"]})
            continue
        ours, theirs = int(row["base_price"]), int(found["base_price"])
        verdicts.append({
            "판정": "VERIFIED" if abs(ours - theirs) <= TOLERANCE_WON else "MISMATCH",
            "carrier": key[0], "plan_name": key[1], "카탈로그가격": ours, "공식가격": theirs,
            "차액": theirs - ours, "source_url": found["source_url"],
        })
    return verdicts


def report(verdicts):
    counts = collections.Counter(verdict["판정"] for verdict in verdicts)
    print(f"VERIFIED {counts['VERIFIED']} · MISMATCH {counts['MISMATCH']} · "
          f"UNVERIFIED {counts['UNVERIFIED']} (대상 {len(verdicts)}행)")
    mismatched = [v for v in verdicts if v["판정"] == "MISMATCH"]
    for verdict in mismatched[:30]:
        print(f"  ! {verdict['carrier']} {verdict['plan_name']}: "
              f"{verdict['카탈로그가격']:,}원 → 공식 {verdict['공식가격']:,}원 "
              f"({verdict['차액']:+,}원)")
    if len(mismatched) > 30:
        print(f"  … MISMATCH {len(mismatched) - 30}건 더 있음")
    if counts["UNVERIFIED"]:
        print(f"  ? 공식 목록에 없는 {counts['UNVERIFIED']}행은 확인 못 한 것이다. 승인을 막지 않는다(D-29)")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--store", type=Path, required=True, help="catalog-store 경로")
    parser.add_argument("--carrier", action="append", choices=sorted(ADAPTERS),
                        help="지정하지 않으면 어댑터가 있는 사업자를 전부 검증한다")
    parser.add_argument("--report", type=Path, help="판정 전체를 CSV로 저장한다")
    arguments = parser.parse_args()

    try:
        revision, catalog = read_plans(arguments.store)
    except (OSError, csv.Error) as error:
        parser.exit(1, f"승인된 카탈로그를 읽지 못했다: {error}\n")

    collected = []
    for index, name in enumerate(arguments.carrier or sorted(ADAPTERS)):
        if index:
            time.sleep(DELAY_SECONDS)   # docs/data.md §3 수집 준수사항
        try:
            rows = list(ADAPTERS[name]())
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
            # 한 사업자가 실패해도 나머지는 검증한다. 실패한 쪽은 판정 대상에서 빠진다.
            print(f"{name}: 조회 실패 — {error}", file=sys.stderr)
            continue
        print(f"{name}: 공식 목록 {len(rows)}건", file=sys.stderr)
        collected += rows

    if not collected:
        parser.exit(1, "공식 목록을 하나도 읽지 못했다. 판정하지 않는다\n")

    verdicts = judge(catalog, collected)
    print(f"리비전 {revision[:12]}", file=sys.stderr)
    report(verdicts)

    if arguments.report:
        with arguments.report.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=REPORT_FIELDS)
            writer.writeheader()
            writer.writerows(verdicts)
        print(f"{arguments.report} 저장")
    # MISMATCH는 사람이 확인할 일이지 스크립트 실패가 아니다.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
