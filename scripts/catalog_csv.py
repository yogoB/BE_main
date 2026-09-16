#!/usr/bin/env python3
"""검수 CSV 발행 도구. 외부 네트워크·AI 호출 없이 파일 검증, 승인 해시, 원자적 버전을 관리한다."""
import argparse
import csv
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
HEADERS = {
    "subscription_service": "id,name,category,official_url",
    "subscription_tier": "id,service_id,name,price,concurrent_streams,quality,note",
    "bundle_product": "id,name,price,provider,tier_ids",
    "mobile_plan": "carrier,plan_name,network_type,base_price,data_mb,voice_min,sms_cnt,contract_discount_12m,contract_discount_24m,age_limit,source_url,collected_at",
    "plan_benefit": "carrier,plan_name,service_id,tier_id,benefit_type,discount_value,is_exclusive,exclusive_group,valid_from,valid_to,source_url,collected_at",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, minimum=0, optional=False):
    if optional and value == "":
        return None
    require(value.isascii() and value.isdigit(), "금액·수량·ID는 정수여야 합니다")
    n = int(value)
    require(minimum <= n <= 2**63 - 1, "정수 범위를 벗어났습니다")
    return n


def url(value):
    u = urlsplit(value)
    require(u.scheme == "https" and bool(u.hostname) and not u.username and not u.password
            and not u.query and not u.fragment, "출처는 인증정보·query·fragment 없는 HTTPS URL이어야 합니다")


def day(value):
    d = date.fromisoformat(value)
    require(d.isoformat() == value and d <= date.today(), "확인일은 미래가 아닌 YYYY-MM-DD여야 합니다")
    return d


def read_catalog(directory):
    blobs, rows = {}, {}
    for name, header in HEADERS.items():
        data = (directory / (name + ".csv")).read_bytes()
        require(len(data) <= 10_000_000, "CSV는 파일당 10MB 이하입니다")
        reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig")), strict=True)
        require(reader.fieldnames == header.split(","), name + ": CSV 헤더 불일치")
        records = list(reader)
        require(len(records) <= 10000, name + ": 행 수 초과")
        for row in records:
            require(None not in row and all(v is not None for v in row.values()), name + ": 열 수 불일치")
            require(all(len(v) <= 2000 and v == v.strip() for v in row.values()), name + ": 길이 또는 주변 공백 오류")
        blobs[name], rows[name] = data.removeprefix(b"\xef\xbb\xbf"), records
    validate(rows)
    return blobs, rows


def validate(data):
    ids = {}
    for name in ("subscription_service", "subscription_tier", "bundle_product"):
        seen = set()
        for row in data[name]:
            i = integer(row["id"], 1)
            require(i not in seen and row["name"], name + ": 중복 ID 또는 빈 이름")
            seen.add(i)
        ids[name] = seen
    require(ids["subscription_service"] and ids["subscription_tier"], "서비스·티어 전체 삭제는 허용하지 않습니다")
    service_names = set()
    for r in data["subscription_service"]:
        url(r["official_url"])
        require(r["category"] and r["name"] not in service_names, "서비스 종류·이름 오류")
        service_names.add(r["name"])
    tiers, tier_names = {}, set()
    for r in data["subscription_tier"]:
        service = integer(r["service_id"], 1)
        require(service in ids["subscription_service"], "티어의 서비스가 없습니다")
        key = (service, r["name"])
        require(key not in tier_names, "중복 티어 이름")
        tier_names.add(key)
        tiers[int(r["id"])] = service
        integer(r["price"])
        integer(r["concurrent_streams"], 1, True)
    for r in data["bundle_product"]:
        integer(r["price"])
        members = [integer(v, 1) for v in r["tier_ids"].split(",")]
        require(set(members) <= ids["subscription_tier"] and len(members) == len(set(members)), "번들 티어 참조 오류")
    plans = set()
    for r in data["mobile_plan"]:
        key = (r["carrier"], r["plan_name"])
        require(all(key) and key not in plans and r["network_type"] in ("5G", "4G", "LTE", "3G"), "요금제 이름·망·중복 오류")
        plans.add(key)
        integer(r["base_price"], 1)
        integer(r["data_mb"])
        # 통화·문자는 공식 표기에 수량이 없으면 비운다(미확인). 0(미제공)과 구분하려고 지어내지 않는다.
        for field in ("voice_min", "sms_cnt"):
            integer(r[field], optional=True)
        for field in ("contract_discount_12m", "contract_discount_24m"):
            integer(r[field], optional=True)
        url(r["source_url"])
        day(r["collected_at"])

    maximum_total = max((int(r["base_price"]) for r in data["mobile_plan"]), default=0)
    maximum_total += sum(int(r["price"]) for name in ("subscription_tier", "bundle_product") for r in data[name])
    require(maximum_total <= (2**63 - 1) // 12, "연간 합계가 long 범위를 초과할 수 있습니다")
    for r in data["plan_benefit"]:
        require((r["carrier"], r["plan_name"]) in plans, "혜택의 요금제가 없습니다")
        service = integer(r["service_id"], 1)
        tier = integer(r["tier_id"], 1, True)
        require(service in ids["subscription_service"] and (tier is None or tiers.get(tier) == service), "혜택 서비스·티어 불일치")
        kind, amount = r["benefit_type"], r["discount_value"]
        if kind in ("FREE", "BUNDLE_INCLUDED"):
            require(not amount, "무료/번들 혜택의 할인 금액은 비워야 합니다")
        elif kind == "FIXED_DISCOUNT":
            integer(amount)
        elif kind == "RATE_DISCOUNT":
            rate = Decimal(amount)
            require(rate.is_finite() and 0 <= rate <= 1 and max(0, -rate.as_tuple().exponent) <= 2, "정률 할인 범위/소수점 오류")
        else:
            raise ValueError("혜택 종류 오류")
        require(r["is_exclusive"] in ("true", "false"), "택1 값은 true/false입니다")
        require(r["is_exclusive"] != "true" or r["exclusive_group"], "택1 그룹이 없습니다")
        if r["valid_from"]:
            date.fromisoformat(r["valid_from"])
        if r["valid_to"]:
            date.fromisoformat(r["valid_to"])
        require(not r["valid_from"] or not r["valid_to"] or r["valid_from"] <= r["valid_to"], "혜택 유효기간 역전")
        url(r["source_url"])
        day(r["collected_at"])


def current(store):
    path = store / "current"
    revision = path.read_text().strip() if path.exists() else ""
    require(not revision or len(revision) == 64 and all(c in "0123456789abcdef" for c in revision), "현재 revision 오류")
    return revision


def atomic_write(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def publish(directory, store, review, allow_deletions=False):
    blobs, rows = read_catalog(directory)
    hashes = {n: digest(b) for n, b in blobs.items()}
    require(review.get("files") == hashes, "검수한 파일과 현재 CSV가 다릅니다. 다시 검수하세요")
    require(isinstance(review.get("approved_by"), str) and review["approved_by"].strip(), "검수자 승인 없음")
    require(isinstance(review.get("reason"), str) and review["reason"].strip(), "변경 사유 없음")
    require(review.get("sources"), "출처·이용 조건 확인 기록이 없습니다")
    for source in review["sources"]:
        url(source["url"])
        day(source["checked_at"])
        require(source.get("usage_basis", "").strip(), "출처의 이용 근거가 없습니다")
    store.mkdir(parents=True, exist_ok=True)
    with (store / ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = current(store)
        require(review.get("expected_revision") == previous, "동시에 다른 버전이 발행됐습니다. 다시 검수하세요")
        if previous:
            _, old_rows = read_catalog(store / "revisions" / previous)
            for name in HEADERS:
                def key(row):
                    if "id" in row:
                        return row["id"]
                    if name == "mobile_plan":
                        return (row["carrier"], row["plan_name"])
                    return tuple(row.values())
                old_keys = {key(r) for r in old_rows[name]}
                new_keys = {key(r) for r in rows[name]}
                require(allow_deletions or old_keys <= new_keys, "삭제/혜택 교체가 있습니다. 검토 후 --allow-deletions 사용")
        manifest = dict(review, created_at=datetime.now(timezone.utc).isoformat(), files=hashes)
        content = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
        revision = digest(content)
        versions = store / "revisions"
        versions.mkdir(exist_ok=True)
        destination = versions / revision
        destination.mkdir()
        for name, blob in blobs.items():
            atomic_write(destination / (name + ".csv"), blob)
        atomic_write(destination / "manifest.json", content)
        atomic_write(store / "current", (revision + "\n").encode())
        return revision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "prepare", "publish"))
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--store", type=Path, required=True)
    parser.add_argument("--review", type=Path)
    parser.add_argument("--allow-deletions", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "init":
            args.input_dir.mkdir(parents=True, exist_ok=False)
            previous = current(args.store)
            source = args.store / "revisions" / previous if previous else ROOT / "db" / "seed"
            for name, header in HEADERS.items():
                path = source / (name + ".csv")
                data = path.read_bytes() if path.exists() else (header + "\n").encode()
                (args.input_dir / (name + ".csv")).write_bytes(data)
            print("작업 사본 생성. 파일 수정 후 prepare → 근거 검수 → publish 순서로 진행하세요.")
        elif args.command == "prepare":
            blobs, rows = read_catalog(args.input_dir)
            print(json.dumps({"expected_revision": current(args.store), "approved_by": "", "reason": "",
                              "sources": [], "files": {n: digest(b) for n, b in blobs.items()},
                              "row_counts": {n: len(r) for n, r in rows.items()}}, ensure_ascii=False, indent=2))
        else:
            require(args.review is not None, "--review 승인 파일이 필요합니다")
            print(publish(args.input_dir, args.store, json.loads(args.review.read_text()), args.allow_deletions))
    except (ValueError, KeyError, TypeError, OSError, csv.Error, InvalidOperation) as error:
        parser.exit(1, f"CSV 반영 거부: {error}\n")


if __name__ == "__main__":
    main()
