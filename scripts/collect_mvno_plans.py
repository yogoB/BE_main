#!/usr/bin/env python3
"""공식 요금제 페이지에서 요금제를 수집해 **작업 사본**의 mobile_plan.csv를 갱신하고 차이를 보고한다.

**런타임 호출 금지**(D-05). 하루 1회 오프라인으로 돌리는 배치이며, 서비스는 발행된 CSV만 읽는다.
이 스크립트는 catalog-store를 건드리지 않는다. 발행은 사람이 승인하는 catalog_csv.py가 한다:

    python3 scripts/catalog_csv.py init    --input-dir work --store ../catalog-store
    python3 scripts/collect_mvno_plans.py  --input-dir work            # ← 여기
    python3 scripts/catalog_csv.py prepare --input-dir work --store ../catalog-store > review.json
    #   사람이 diff를 보고 approved_by·reason·sources를 채운다
    python3 scripts/catalog_csv.py publish --input-dir work --store ../catalog-store --review review.json

하루 1회 실행 예(crontab -e). 작업 사본을 날짜별로 새로 만들고 보고서를 남긴다:

    5 4 * * * cd /경로/yogoB/BE_main && D=/tmp/catalog-$(date +\%F) && \
      python3 scripts/catalog_csv.py init --input-dir $D --store ../catalog-store && \
      python3 scripts/collect_mvno_plans.py --input-dir $D > $D/report.txt 2>&1

발행은 사람이 report.txt를 보고 판단한다. 자동 발행하지 않는다.

행을 지우지 않는다. 사라진 요금제는 보고만 하고 사람이 판단한다(판매 종료인지 일시적 누락인지
수집만으로는 구분할 수 없다). 요청 간 1초 이상 간격을 둔다(docs/data.md §3).
"""
import argparse
import csv
import re
import io
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

HEADER = ("carrier,plan_name,network_type,base_price,data_mb,voice_min,sms_cnt,"
          "contract_discount_12m,contract_discount_24m,age_limit,source_url,collected_at").split(",")
AGENT = "Mozilla/5.0 (compatible; yogobi-catalog/1.0; +offline batch, 1 req/s)"
UNLIMITED = 999999
DELAY_SECONDS = 1.0
# 공식 자료에서 그대로 읽히는 값만 덮어쓴다.
# network_type·age_limit은 사람이 상품 조건을 보고 분류한 값이다("복지", "청년 만 19~34세" 등
# 18종). 수집기가 이름만 보고 덮으면 검수 결과가 조용히 지워진다.
AUTHORITATIVE = ("base_price", "data_mb", "voice_min", "sms_cnt",
                 "contract_discount_12m", "contract_discount_24m", "collected_at")
FIVE_G = re.compile(r"5G(?!B)")   # "LTE 유심 (5GB/100분)"의 5GB를 5G 망으로 읽지 않는다


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


def megabytes(amount, unit):
    """데이터 제공량을 MB로 맞춘다. 표기가 수치가 아니면 무제한으로 본다."""
    size = to_int(amount, default=None)
    if size is None:
        return UNLIMITED
    return size * 1024 if (unit or "").upper() == "GB" else size


def allowance(amount, unit):
    """'기본제공'·'무제한'처럼 수치가 없는 표기는 무제한으로 본다."""
    return to_int(amount, default=UNLIMITED) if (unit or "").strip() else UNLIMITED


def network_of(name):
    """새 요금제의 잠정 분류. 기존 행의 분류는 건드리지 않는다."""
    upper = name.upper()
    return "5G" if FIVE_G.search(upper) else "3G" if "3G" in upper else "LTE"


def collect_sk7mobile(today):
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
        yield {
            "carrier": "SK세븐모바일", "plan_name": name, "network_type": network_of(name),
            "base_price": price,
            "data_mb": megabytes(item.get("basicFreeData"), item.get("basicFreeDataUnit")),
            "voice_min": allowance(item.get("basicFreeVoice"), item.get("basicFreeVoiceUnit")),
            "sms_cnt": allowance(item.get("basicFreeSms"), item.get("basicFreeSmsUnit")),
            "contract_discount_12m": to_int(item.get("enggDcAmt12")) or "",
            "contract_discount_24m": to_int(item.get("enggDcAmt24")) or "",
            "age_limit": "ALL", "source_url": source, "collected_at": today,
        }


ADAPTERS = {"sk7mobile": collect_sk7mobile}


def read_plans(path):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def write_plans(path, rows):
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=HEADER, extrasaction="ignore", lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    path.write_text(buffer.getvalue(), encoding="utf-8")


def merge(existing, collected):
    """(carrier, plan_name)으로 맞춘다. 발행 도구가 쓰는 키와 같다."""
    index = {(row["carrier"], row["plan_name"]): row for row in existing}
    added, changed, seen = [], [], set()
    for row in collected:
        key = (row["carrier"], row["plan_name"])
        seen.add(key)
        row = {field: str(row.get(field, "")) for field in HEADER}
        previous = index.get(key)
        if previous is None:
            index[key] = row
            added.append(row)
            continue
        differences = {f: (previous[f], row[f]) for f in AUTHORITATIVE
                       if f != "collected_at" and previous.get(f, "") != row[f]}
        if differences:
            changed.append((key, differences))
        # 값이 같아도 확인 시각은 갱신한다. 언제까지 유효했는지가 남는다.
        previous.update({f: row[f] for f in AUTHORITATIVE})
    carriers = {row["carrier"] for row in collected}
    missing = [key for key, row in index.items()
               if row["carrier"] in carriers and key not in seen]
    return list(index.values()), added, changed, missing


def report(added, changed, missing):
    print(f"신규 {len(added)}건 · 변경 {len(changed)}건 · 목록에서 사라짐 {len(missing)}건")
    for row in added[:20]:
        print(f"  + {row['carrier']} {row['plan_name']} {int(row['base_price']):,}원"
              f" [{row['network_type']} 잠정 분류 — 검수 필요]")
    for key, differences in changed[:20]:
        detail = ", ".join(f"{f} {old or '없음'}→{new or '없음'}" for f, (old, new) in differences.items())
        print(f"  ~ {key[0]} {key[1]}: {detail}")
    for key in missing[:20]:
        print(f"  ? {key[0]} {key[1]} — 공식 목록에 없음. 판매 종료인지 확인 필요(자동 삭제하지 않음)")
    for label, items in (("신규", added), ("변경", changed), ("사라짐", missing)):
        if len(items) > 20:
            print(f"  … {label} {len(items) - 20}건 더 있음")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input-dir", type=Path, required=True, help="catalog_csv.py init이 만든 작업 사본")
    parser.add_argument("--carrier", action="append", choices=sorted(ADAPTERS),
                        help="지정하지 않으면 전부 수집한다")
    parser.add_argument("--dry-run", action="store_true", help="수집·비교만 하고 파일을 쓰지 않는다")
    parser.add_argument("--collected-at", default=date.today().isoformat())
    arguments = parser.parse_args()

    path = arguments.input_dir / "mobile_plan.csv"
    if not path.exists():
        parser.exit(1, f"{path}이 없다. 먼저 catalog_csv.py init을 실행한다\n")

    collected = []
    for index, name in enumerate(arguments.carrier or sorted(ADAPTERS)):
        if index:
            time.sleep(DELAY_SECONDS)   # docs/data.md §3 수집 준수사항
        try:
            rows = list(ADAPTERS[name](arguments.collected_at))
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
            # 한 사업자가 실패해도 나머지는 수집한다. 실패한 쪽은 기존 행이 그대로 남는다.
            print(f"{name}: 수집 실패 — {error}", file=sys.stderr)
            continue
        print(f"{name}: {len(rows)}건 수집", file=sys.stderr)
        collected += rows

    if not collected:
        parser.exit(1, "수집된 요금제가 없다. 파일을 고치지 않았다\n")

    merged, added, changed, missing = merge(read_plans(path), collected)
    report(added, changed, missing)
    if arguments.dry_run:
        print("--dry-run: 파일을 고치지 않았다")
        return 0
    write_plans(path, merged)
    print(f"{path} 갱신 ({len(merged)}행). 발행하려면 catalog_csv.py prepare → publish")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
