#!/usr/bin/env python3
"""가격 판정 규칙(D-29) 자체 검사. 표준 라이브러리만 쓰고 네트워크를 타지 않는다.

실행: python3 scripts/test_verify_mvno_plans.py
"""
import sys

from verify_mvno_plans import TOLERANCE_WON, judge, to_int

FAILURES = []


def check(label, actual, expected):
    if actual != expected:
        FAILURES.append(f"{label}: {actual!r} != {expected!r}")


def catalog_row(name="LTE 유심 (5GB/100분)", price="9920", carrier="SK세븐모바일"):
    return {"carrier": carrier, "plan_name": name, "base_price": price,
            "source_url": "https://x.example/list"}


def official(name="LTE 유심 (5GB/100분)", price=9920, carrier="SK세븐모바일"):
    return {"carrier": carrier, "plan_name": name, "base_price": price,
            "source_url": "https://x.example/list"}


def verdicts_of(catalog, collected):
    return [v["판정"] for v in judge(catalog, collected)]


check("쉼표 금액", to_int("33,000"), 33000)
check("빈 값", to_int(""), "")

# 같은 금액이면 VERIFIED.
check("일치", verdicts_of([catalog_row()], [official()]), ["VERIFIED"])

# D-29: 100원 이내 차이는 표기 반올림으로 보고 같은 값으로 친다.
check("허용오차 경계", verdicts_of([catalog_row(price="9920")],
                              [official(price=9920 + TOLERANCE_WON)]), ["VERIFIED"])
check("허용오차 초과", verdicts_of([catalog_row(price="9920")],
                              [official(price=9920 + TOLERANCE_WON + 1)]), ["MISMATCH"])
check("싸진 경우도 MISMATCH", verdicts_of([catalog_row(price="9920")],
                                     [official(price=8000)]), ["MISMATCH"])

# 공식 목록에 없으면 "틀렸다"가 아니라 "확인 못 했다"다. 승인을 막지 않는다.
check("목록에 없음", verdicts_of([catalog_row(name="사라진 요금제")], [official()]),
      ["UNVERIFIED"])

# 어댑터가 없는 사업자는 판정 대상이 아니다. 확인 못 한 것과 구분한다.
check("다른 사업자 제외", verdicts_of([catalog_row(carrier="KT엠모바일")], [official()]), [])

# 차액 부호는 공식 - 카탈로그다. 올랐는지 내렸는지가 리포트에서 바로 읽혀야 한다.
verdict = judge([catalog_row(price="9920")], [official(price=12000)])[0]
check("차액 부호", verdict["차액"], 2080)
check("공식가격 보고", verdict["공식가격"], 12000)

print("\n".join(FAILURES) or "모든 검사 통과")
sys.exit(1 if FAILURES else 0)
