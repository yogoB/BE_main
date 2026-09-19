#!/usr/bin/env python3
"""계약 원본과 내레이터 사본이 어긋났는지 본다.

원본은 `BE_main/docs/architecture.md` §3 이고 사본은 `AI-/docs/contract.md` 다(크로스 레포 규칙).
사람이 눈으로 대조하면 놓친다 — 2026-09-20 에 사본에서 '허용 오차 0' 이, 원본 502 행에서
`detail.code` 위치가 빠진 것이 둘 다 스크립트로 나왔다.

**검사기를 먼저 의심하게 만드는 가드가 이 파일의 핵심이다.** 같은 날 양쪽에서 경계 표지를
잘못 잡아 슬라이스가 비었다. 한쪽은 '전부 없음'(이상해 보임), 다른 쪽은 '전부 일치'(안심하고 넘어감)
로 나왔다. **뒤가 더 나쁘다.** 그래서 빈 슬라이스는 결과를 내기 전에 죽는다.

**한계를 알고 쓴다.** 표지가 문서 어딘가에 **한 번이라도** 있으면 통과다. 그래서 같은 값을 여러 번
적은 절에서 한 군데만 바뀐 경우는 못 잡는다 — "항목이 통째로 빠진 것" 을 잡는 도구이지
문장을 대조하는 도구가 아니다. (2026-09-20: 이 한계를 모르고 3번 등장하는 표지를 1번만 지워
테스트했다가 "탐지 못함" 으로 오해했다. 검사 대상이 아니라 **측정 방법**이 틀렸던 것이다.)

사용법: python3 scripts/contract_audit.py [--ai <AI- 경로>]
사본이 없으면 건너뛴다(0 종료) — 이 레포만 받은 사람에게 실패로 보이면 안 된다.
"""
import argparse, pathlib, sys

MIN_SECTION_CHARS = 200

# (이름, 원본에도 사본에도 있어야 하는 표지)
CHECKS = [
    ("요청 serviceName 셋", ["serviceName", "Apple Music", "iCloud+"]),
    ("200 필드", ["sourceUrl", "checkedAt", "sourceHash", "tierName", "billingPeriod", "evidence"]),
    ("502 코드 둘", ["CATALOG-SOURCE-UNAVAILABLE", "CATALOG-SOURCE-CHANGED"]),
    ("URL 요청 미수신(SSRF)", ["요청으로 받지 않는다"]),
    ("쿨다운 주체", ["쿨다운"]),
    ("허용 오차 0", ["오차 0"]),
    ("detail.code 위치", ["detail.code"]),
]


def section(text, start_marker, label):
    """제목부터 다음 같은 수준 제목까지. 비면 원본이 아니라 경계 표지를 의심한다."""
    i = text.find(start_marker)
    if i < 0:
        sys.exit(f"[검사기 오류] {label}: 시작 표지를 못 찾았다 — {start_marker!r}")
    level = start_marker.split(" ", 1)[0] + " "          # '###' 또는 '##'
    j = text.find("\n" + level, i + len(start_marker))
    body = text[i: j if j > 0 else len(text)]
    if len(body) < MIN_SECTION_CHARS:
        sys.exit(f"[검사기 오류] {label}: 슬라이스가 {len(body)}자뿐이다. 경계 표지를 의심하라 — "
                 "빈 슬라이스는 '전부 일치'로 보여서 안심하고 넘어가게 만든다")
    return body


def main():
    here = pathlib.Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser()
    ap.add_argument("--ai", default=str(here.parent / "AI-"))
    args = ap.parse_args()

    copy_path = pathlib.Path(args.ai) / "docs" / "contract.md"
    if not copy_path.exists():
        print(f"사본이 없어 건너뛴다: {copy_path}")
        return 0

    origin = section((here / "docs" / "architecture.md").read_text(encoding="utf-8"),
                     "### 구독 공식가 조회 (D-60", "원본 D-60")
    copy = section(copy_path.read_text(encoding="utf-8"),
                   "## 8. POST /operations/subscriptions/check", "사본 §8")
    print(f"원본 {len(origin)}자 · 사본 {len(copy)}자")

    bad = 0
    for name, markers in CHECKS:
        in_origin = all(m in origin for m in markers)
        in_copy = all(m in copy for m in markers)
        mark = "O" if in_origin and in_copy else "X"
        if mark == "X":
            bad += 1
            missing = "원본" if not in_origin else "사본"
            print(f"  {mark}  {name} — {missing}에 없다")
        else:
            print(f"  {mark}  {name}")
    if bad:
        print(f"\n{bad}개 항목이 어긋났다. 같은 날 맞춘다(크로스 레포 규칙).")
        return 1
    print("\n원본과 사본이 항목별로 일치한다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
