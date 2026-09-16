#!/usr/bin/env python3
"""수집 병합 규칙 자체 검사. 표준 라이브러리만 쓰고 네트워크를 타지 않는다.

실행: python3 scripts/test_collect_mvno_plans.py
"""
import sys

from collect_mvno_plans import HEADER, allowance, megabytes, merge, network_of

FAILURES = []


def check(label, actual, expected):
    if actual != expected:
        FAILURES.append(f"{label}: {actual!r} != {expected!r}")


def row(**over):
    base = dict.fromkeys(HEADER, "")
    base.update(carrier="SK세븐모바일", plan_name="LTE 유심 (5GB/100분)", network_type="LTE",
                base_price="9920", data_mb="5120", voice_min="100", sms_cnt="100",
                age_limit="복지", source_url="https://x.example/list", collected_at="2026-09-08")
    base.update(over)
    return base


# 요금제명의 "5GB"를 5G 망으로 읽지 않는다.
check("5GB는 LTE", network_of("LTE 유심 (5GB/100분)"), "LTE")
check("5G는 5G", network_of("5G 유심 (200GB+)"), "5G")
check("3G", network_of("3G 표준"), "3G")

# 수치가 없는 표기는 무제한이다. 카탈로그의 999999와 같은 뜻이다.
check("기본제공 통화", allowance("기본제공", ""), 999999)
check("200분", allowance("200", "분"), 200)
check("GB→MB", megabytes("3", "GB"), 3072)
check("MB 그대로", megabytes("500", "MB"), 500)

# 사람이 분류한 값은 수집기가 덮지 않는다.
collected = [dict(row(), network_type="5G", age_limit="ALL", base_price="8800",
                  collected_at="2026-09-16")]
merged, added, changed, missing = merge([row()], collected)
check("신규 없음", added, [])
check("가격 변경 1건", [f for _, d in changed for f in d], ["base_price"])
check("사람 분류 보존(망)", merged[0]["network_type"], "LTE")
check("사람 분류 보존(대상)", merged[0]["age_limit"], "복지")
check("가격은 갱신", merged[0]["base_price"], "8800")
check("확인 시각 갱신", merged[0]["collected_at"], "2026-09-16")

# 값이 같아도 확인 시각은 올라간다 — 언제까지 유효했는지가 남는다.
merged, _, changed, _ = merge([row()], [dict(row(), collected_at="2026-09-16")])
check("변경 없음", changed, [])
check("확인 시각만 갱신", merged[0]["collected_at"], "2026-09-16")

# 공식 목록에서 사라진 행은 지우지 않고 보고만 한다.
merged, added, _, missing = merge([row(), row(plan_name="사라진 요금제")], [row()])
check("행 보존", len(merged), 2)
check("사라짐 보고", missing, [("SK세븐모바일", "사라진 요금제")])

# 수집하지 않은 사업자의 행을 사라졌다고 하지 않는다.
_, _, _, missing = merge([row(carrier="KT엠모바일")], [row()])
check("다른 사업자 제외", missing, [])

print("\n".join(FAILURES) or "모든 검사 통과")
sys.exit(1 if FAILURES else 0)
