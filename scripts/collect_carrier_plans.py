#!/usr/bin/env python3
r"""통신사 공식 목록에서 요금제를 수집해 **갱신 제안**을 만든다(D-43).

**런타임 호출 금지**(D-05). 오프라인 배치이며 서비스는 승인된 CSV 만 읽는다.
이 스크립트는 합본 CSV 를 직접 고치지 않는다 — 두 파일을 내놓고 사람이 검수한다.

    <출력>/mobile_plan_updated.csv   기존 행의 금액만 갱신한 전체 표(합본에 넣을 수 있는 형태)
    <출력>/report.csv                행별 판정(변경·동일·확인못함·신규)

**사람이 분류한 값은 덮지 않는다.** network_type·age_limit 은 상품 조건을 보고 사람이 정한 값이라
(`복지`, `청년 만 19~34세` 등) 이름만 보고 덮으면 검수 결과가 조용히 지워진다. 갱신 대상은
공식 목록에서 그대로 읽히는 금액과 확인일뿐이다.

**신규 요금제는 합본 표에 넣지 않는다.** 대신 `mobile_plan_new.csv` 에 **채울 칸을 비운 채로** 내놓는다.
이름·금액·출처·확인일은 공식 목록에서 그대로 읽히지만 `data_mb`·`voice_min`·`sms_cnt`·`network_type`·
`age_limit` 은 그렇지 않다 — 같은 "7GB+" 표기를 카탈로그가 7,168 로도 999,999(무제한)로도 적고 있어
기계적으로 재현되지 않는다. 사람이 채워야 계산에 쓸 수 있다(D-43 의 검수 요건).

요청 간 1초 이상 간격을 둔다(docs/data.md §3).

    python3 scripts/collect_carrier_plans.py --out /tmp/catalog-refresh
    python3 scripts/collect_carrier_plans.py --out /tmp/x --carrier SK세븐모바일 --dry-run
"""
import argparse
import csv
import html
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMBINED = ROOT / "db" / "seed" / "catalog_combined.csv"
AGENT = "Mozilla/5.0 (compatible; yogobi-catalog/1.0; +offline batch, 1 req/s)"
DELAY = 1.0
# 공식 목록에서 그대로 읽히는 값만 갱신한다. 나머지 열은 사람이 정한 값이라 건드리지 않는다.
REFRESHABLE = ("base_price", "collected_at")
REPORT_FIELDS = ["판정", "carrier", "plan_name", "기존가격", "공식가격", "차액", "source_url"]


def fetch(url, data=None, form=False):
    body = None
    headers = {"User-Agent": AGENT, "X-Requested-With": "XMLHttpRequest"}
    if data is not None:
        if form:
            body = data.encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        else:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers)
    with urllib.request.urlopen(request, timeout=25) as response:
        return response.read().decode("utf-8", "replace")


def won(value):
    digits = "".join(c for c in str(value if value is not None else "") if c.isdigit())
    return int(digits) if digits else None


def sk7mobile():
    """basicAmt 는 정가 표시, basicAmt2 가 실제 판매가다."""
    payload = json.loads(fetch("https://www.sk7mobile.com/prod/data/searchPlanList.do", {}))
    out = {}
    for item in payload.get("resultList") or []:
        name = (item.get("prodNm") or "").strip()
        price = won(item.get("basicAmt2")) or won(item.get("basicAmt"))
        if name and price is not None:
            out.setdefault(name, price)
    return out, "https://www.sk7mobile.com/prod/data/callingPlanList.do"


def lg_hellovision():
    """Directmall 은 한시 프로모션가, AfterPrice 가 종료 후 정상가다."""
    payload = json.loads(fetch("https://direct.lghellovision.net/fund/ajaxRateList.do", "", form=True))
    out = {}
    for item in payload.get("list") or []:
        name = (item.get("salesName") or "").strip()
        price = won(item.get("directPromotionAfterPrice")) or won(item.get("directPromotionDirectmallPrice"))
        if name and price is not None:
            out.setdefault(name, price)
    return out, "https://direct.lghellovision.net/rate/rateView.do"


def ktm_mobile():
    """요금제가 카테고리로 나뉘어 있어 카테고리 목록 한 번 + 카테고리마다 한 번 받는다."""
    categories = json.loads(fetch("https://www.ktmmobile.com/rate/getCtgXmlAllListAjax.do",
                                  "rateAdsvcDivCd=RATE", form=True))
    out = {}
    for index, category in enumerate(categories if isinstance(categories, list) else []):
        code = str(category.get("rateAdsvcCtgCd") or "")
        if not code:
            continue
        if index:
            time.sleep(DELAY)
        for item in json.loads(fetch("https://www.ktmmobile.com/rate/rateContentAjax.do",
                                     f"rateAdsvcCtgCd={code}", form=True)) or []:
            name = (item.get("rateAdsvcNm") or "").strip()
            price = won(item.get("mmBasAmtVatDesc")) or won(item.get("mmBasAmtDesc"))
            if name and price is not None:
                out.setdefault(name, price)
    return out, "https://www.ktmmobile.com/rate/rateList.do"


def uplus_umobile():
    """목록이 서버에서 렌더된다. data-bscChrgAddVat 가 정가, data-p0PrPrcAddVat 는 할인 적용가다."""
    page = fetch("https://www.uplusumobile.com/product/pric/usim/pricList")
    out = {}
    for match in re.finditer(r'data-ppnNm="([^"]+)"(.{0,400}?)data-p0PrPrcAddVat="\d+"', page, re.S):
        name = html.unescape(match.group(1)).strip()
        basic = re.search(r'data-bscChrgAddVat="(\d+)"', match.group(2))
        if name and basic:
            out.setdefault(name, int(basic.group(1)))
    return out, "https://www.uplusumobile.com/product/pric/usim/pricList"


CARRIERS = {
    "SK세븐모바일": sk7mobile,
    "LG헬로모바일": lg_hellovision,
    "KT엠모바일": ktm_mobile,
    "U+유모바일": uplus_umobile,
}


def read_section(text, dataset):
    for block in re.split(r"^#@ ", text, flags=re.M)[1:]:
        lines = block.splitlines()
        if lines[0].strip() != dataset:
            continue
        rows = [line for line in lines[1:] if line.strip() and not line.startswith("#")]
        return list(csv.DictReader(io.StringIO("\n".join(rows)))), rows[0].split(",")
    raise ValueError(f"{dataset} 섹션을 찾지 못했다")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--carrier", action="append", choices=sorted(CARRIERS))
    parser.add_argument("--combined", type=Path, default=COMBINED)
    parser.add_argument("--collected-at", default=date.today().isoformat())
    parser.add_argument("--dry-run", action="store_true", help="수집·비교만 하고 파일을 쓰지 않는다")
    arguments = parser.parse_args()

    plans, header = read_section(arguments.combined.read_text(encoding="utf-8-sig"), "mobile_plan")
    official, sources = {}, {}
    for index, name in enumerate(arguments.carrier or sorted(CARRIERS)):
        if index:
            time.sleep(DELAY)
        try:
            official[name], sources[name] = CARRIERS[name]()
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError, json.JSONDecodeError) as error:
            # 한 사업자가 실패해도 나머지는 수집한다. 실패한 쪽 행은 그대로 남는다.
            print(f"{name}: 수집 실패 — {error}", file=sys.stderr)
        else:
            print(f"{name}: 공식 목록 {len(official[name])}건", file=sys.stderr)
    if not official:
        parser.exit(1, "공식 목록을 하나도 받지 못했다. 아무것도 쓰지 않았다\n")

    report, changed = [], 0
    seen = {name: set() for name in official}
    for row in plans:
        carrier = row["carrier"]
        if carrier not in official:
            continue
        name = row["plan_name"]
        seen[carrier].add(name)
        price = official[carrier].get(name)
        entry = {"carrier": carrier, "plan_name": name, "기존가격": row["base_price"],
                 "공식가격": "", "차액": "", "source_url": sources[carrier]}
        if price is None:
            report.append({**entry, "판정": "확인 못 함"})
            continue
        entry["공식가격"] = price
        entry["차액"] = price - int(row["base_price"])
        if entry["차액"]:
            row["base_price"] = str(price)
            row["collected_at"] = arguments.collected_at
            changed += 1
            report.append({**entry, "판정": "변경"})
        else:
            row["collected_at"] = arguments.collected_at
            report.append({**entry, "판정": "동일"})

    for carrier, found in official.items():
        for name, price in found.items():
            if name not in seen[carrier]:
                report.append({"판정": "신규(검수 필요)", "carrier": carrier, "plan_name": name,
                               "기존가격": "", "공식가격": price, "차액": "",
                               "source_url": sources[carrier]})

    counts = {}
    for entry in report:
        counts[entry["판정"]] = counts.get(entry["판정"], 0) + 1
    print(f"대상 {sum(1 for r in plans if r['carrier'] in official)}행 — " +
          " · ".join(f"{k} {v}" for k, v in sorted(counts.items())), file=sys.stderr)
    for entry in [e for e in report if e["판정"] == "변경"][:20]:
        print(f"  ~ {entry['carrier']} {entry['plan_name']}: "
              f"{int(entry['기존가격']):,} → {entry['공식가격']:,} ({entry['차액']:+,})", file=sys.stderr)

    if arguments.dry_run:
        print("--dry-run: 파일을 쓰지 않았다", file=sys.stderr)
        return 0

    arguments.out.mkdir(parents=True, exist_ok=True)
    table = arguments.out / "mobile_plan_updated.csv"
    with table.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=header, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(plans)
    new_rows = [
        {**{column: "" for column in header},
         "carrier": entry["carrier"], "plan_name": entry["plan_name"],
         "base_price": entry["공식가격"], "source_url": entry["source_url"],
         "collected_at": arguments.collected_at}
        for entry in report if entry["판정"].startswith("신규")
    ]
    fresh = arguments.out / "mobile_plan_new.csv"
    with fresh.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=header, lineterminator="\n")
        writer.writeheader()
        writer.writerows(new_rows)

    summary = arguments.out / "report.csv"
    with summary.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=REPORT_FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(report)
    print(f"{table} ({len(plans)}행, 금액 {changed}건 갱신) · {fresh} (신규 {len(new_rows)}행, 제공량 칸 비어 있음)"
          f" · {summary} ({len(report)}행)", file=sys.stderr)
    print("합본에 넣으려면 report.csv 를 검수한 뒤 catalog_csv.py init → 표 교체 → prepare → publish", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
