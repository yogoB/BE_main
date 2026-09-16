#!/usr/bin/env python3
"""매트릭스 → mobile_plan.csv 변환 규칙 자체 검사. 표준 라이브러리만 쓴다.

실행: python3 scripts/test_catalog_matrix_to_csv.py
"""
import io
import sys

from catalog_matrix_to_csv import convert

COLUMNS = ("행번호,통신사구분,브랜드,사용망,요금제명,통신규격,월정액(원),프로모션가(원),프로모션개월,데이터,데이터GB,"
           "소진후속도,소진후Mbps,테더링GB,통화,문자,가입대상,연령조건,대상자격,약정,허브전용,오늘가입가능,개인가입가능,"
           "추천판정,판정사유,판매상태,검증등급,확인일,신규등재일,중복그룹,중복대표,공식상품ID,수집경로,비고,출처URL")


def row(번호, 브랜드, 요금제명, 통신규격="LTE", 월정액="19800", 데이터GB="11", 통화="무제한", 문자="무제한",
        추천판정="추천 가능", 판매상태="가입가능", 검증등급="공식확인", 확인일="2026-09-08", 중복대표="",
        가입대상="일반", 연령조건="", 출처URL="https://example.com/plans"):
    cells = [str(번호), "MVNO", 브랜드, "KT", 요금제명, 통신규격, 월정액, "", "", "", 데이터GB,
             "", "", "", 통화, 문자, 가입대상, 연령조건, "", "선택약정 가능", "N", "오늘 가입 가능", "개인 가입 가능",
             추천판정, "", 판매상태, 검증등급, 확인일, "", "", 중복대표, "", "전체", "", 출처URL]
    return ",".join(cells)


def run(lines, include_conditional=True):
    return convert(io.StringIO("\n".join([COLUMNS] + lines) + "\n"), include_conditional)


def test_units_and_unlimited():
    rows, _ = run([row(1, "A모바일", "11GB 요금제", 데이터GB="11", 통화="100분", 문자="300건")])
    assert rows[0]["data_mb"] == str(11 * 1024), rows[0]
    assert (rows[0]["voice_min"], rows[0]["sms_cnt"]) == ("100", "300"), rows[0]

    rows, _ = run([row(1, "A모바일", "무제한", 데이터GB="무제한", 통화="무제한", 문자="무제한")])
    assert rows[0]["data_mb"] == "999999" and rows[0]["voice_min"] == "999999"


def test_unknown_allowance_is_blank_not_zero():
    """'기본제공'은 수량을 모른다 — 빈칸(미확인). '미제공'은 실제로 0이다."""
    rows, _ = run([row(1, "A모바일", "기본제공형", 통화="기본제공", 문자="문자 기본제공")])
    assert rows[0]["voice_min"] == "" and rows[0]["sms_cnt"] == "", rows[0]

    rows, _ = run([row(1, "A모바일", "데이터전용", 통화="미제공", 문자="미제공")])
    assert rows[0]["voice_min"] == "0" and rows[0]["sms_cnt"] == "0", rows[0]


def test_network_mapping_and_drop():
    rows, _ = run([row(1, "A모바일", "통합요금제", 통신규격="5G/LTE")])
    assert rows[0]["network_type"] == "5G", rows[0]        # 통합 상품은 5G 가입 기준

    rows, dropped = run([row(1, "A모바일", "미표기요금제", 통신규격="미표기")])
    assert rows == [] and dropped["망 매핑 불가(미표기)"] == 1, dropped


def test_excluded_rows_are_counted_not_silently_dropped():
    rows, dropped = run([
        row(1, "A모바일", "종료된요금제", 판매상태="판매종료(확정)"),
        row(2, "A모바일", "2차자료요금제", 검증등급="2차자료"),
        row(3, "A모바일", "가격없음", 월정액=""),
        row(4, "A모바일", "데이터미확인", 데이터GB=""),
    ])
    assert rows == [], rows
    assert sum(dropped.values()) == 4, dropped


def test_conditional_rows_follow_the_flag():
    lines = [row(1, "A모바일", "조건부요금제", 추천판정="조건부 추천")]
    assert len(run(lines, include_conditional=True)[0]) == 1
    assert run(lines, include_conditional=False)[0] == []


def test_duplicate_key_keeps_one_preferring_verified():
    """(통신사, 요금제명)은 DB 자연키다 — 중복이 남으면 한 문장 upsert 가 실패한다."""
    rows, dropped = run([
        row(1, "A모바일", "같은이름", 검증등급="장기미검증", 월정액="11000"),
        row(2, "A모바일", "같은이름", 검증등급="공식확인", 월정액="22000"),
    ])
    assert len(rows) == 1 and rows[0]["base_price"] == "22000", rows
    assert dropped["중복 키(후순위)"] == 1, dropped


def test_source_must_be_https():
    rows, dropped = run([row(1, "A모바일", "출처없음", 출처URL="")])
    assert rows == [] and dropped["출처 URL 없음/비HTTPS"] == 1, dropped

    rows, dropped = run([row(1, "A모바일", "http출처", 출처URL="http://example.com/plans")])
    assert rows == [] and dropped["출처 URL 없음/비HTTPS"] == 1, dropped


def test_source_query_and_fragment_are_stripped():
    """발행 도구가 query·fragment 없는 HTTPS 만 받는다. 붙어 있으면 떼고 통과시킨다."""
    rows, _ = run([row(1, "A모바일", "쿼리출처", 출처URL="https://example.com/list#top")])
    assert rows[0]["source_url"] == "https://example.com/list", rows[0]


def main():
    tests = [value for name, value in sorted(globals().items()) if name.startswith("test_")]
    for test in tests:
        test()
    print(f"{len(tests)}개 통과")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, __file__.rsplit("/", 1)[0])
    sys.exit(main())
