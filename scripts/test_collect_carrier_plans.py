#!/usr/bin/env python3
"""수집 갱신 규칙 자체 검사. 표준 라이브러리만 쓰고 네트워크를 타지 않는다.

실행: python3 scripts/test_collect_carrier_plans.py
"""
import sys

from collect_carrier_plans import read_section, won

FAILURES = []


def check(label, actual, expected):
    if actual != expected:
        FAILURES.append(f"{label}: {actual!r} != {expected!r}")


check("쉼표 금액", won("33,000"), 33000)
check("숫자 아님", won("미정"), None)
check("빈 값", won(""), None)
check("None", won(None), None)
check("실수 표기", won(0.0), 0)

COMBINED = """# 주석
#@ mobile_plan
carrier,plan_name,network_type,base_price,data_mb,age_limit,source_url,collected_at
SK세븐모바일,A,LTE,9920,3072,복지,https://x.example,2026-09-08

#@ subscription_service
id,name
1,넷플릭스
"""
rows, header = read_section(COMBINED, "mobile_plan")
check("섹션 행수", len(rows), 1)
check("헤더", header, ["carrier", "plan_name", "network_type", "base_price",
                      "data_mb", "age_limit", "source_url", "collected_at"])
check("주석 제외", rows[0]["carrier"], "SK세븐모바일")
check("다른 섹션 미포함", read_section(COMBINED, "subscription_service")[0][0]["name"], "넷플릭스")

# 갱신 대상은 금액과 확인일뿐이다. 사람이 분류한 값은 이름만 보고 덮지 않는다.
from collect_carrier_plans import REFRESHABLE
check("갱신 대상", REFRESHABLE, ("base_price", "collected_at"))
for human in ("network_type", "age_limit", "data_mb", "voice_min", "sms_cnt"):
    if human in REFRESHABLE:
        FAILURES.append(f"{human} 은 사람이 정한 값이라 갱신 대상이 아니어야 한다")

print("\n".join(FAILURES) or "모든 검사 통과")
sys.exit(1 if FAILURES else 0)
