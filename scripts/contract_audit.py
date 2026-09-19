#!/usr/bin/env python3
"""계약 원본과 내레이터 사본이 어긋났는지 본다.

원본은 `BE_main/docs/architecture.md` §3 이고 사본은 `AI-/docs/contract.md` 다(크로스 레포 규칙).
사람이 눈으로 대조하면 놓친다 — 2026-09-20 에 사본에서 '허용 오차 0' 이, 원본 502 행에서
`detail.code` 위치가 빠진 것이 둘 다 스크립트로 나왔다.

**검사기를 먼저 의심하게 만드는 가드가 이 파일의 핵심이다.** 같은 날 양쪽에서 경계 표지를
잘못 잡아 슬라이스가 비었다. 한쪽은 '전부 없음'(이상해 보임), 다른 쪽은 '전부 일치'(안심하고 넘어감)
로 나왔다. **뒤가 더 나쁘다.** 그래서 빈 슬라이스는 결과를 내기 전에 죽는다.

**이 검사기가 하는 일은 하나다: 항목이 통째로 빠졌는지 본다.**

**값은 대조하지 않는다.** 표지 문자열이 문서 어딘가에 한 번이라도 있으면 통과다. 그래서 아래는
**진짜 계약 위반인데 전부 통과한다**(2026-09-20 실측):

| 훼손 | 결과 |
|---|---|
| 페이지 크기 상한 `1MB` → `10MB` | 통과 |
| 대상 서비스 `iCloud+` → `OneDrive` (두 곳 중 한 곳) | 통과 |
| **"호출 간격은 BE 가 지킨다" → "AI 가 지킨다"** | **통과** — 표지가 `쿨다운` 뿐이라 주체가 뒤집혀도 모른다 |
| 예시 가격 `8,900` → `9,900` | 통과 |
| **`허용 오차 0.` 문자열은 남기고 규칙만 "100원 이내면 무시" 로 뒤집기** | **통과** |

마지막에서 두 번째가 제일 위험하다. 검사 항목 이름이 "쿨다운 주체" 라서 **주체가 지켜진다고 읽히지만
실제로는 그 낱말이 있는지만 본다.** 이름이 기능보다 크다.

**값 대조는 사람이 한다.** 이 파일을 문장 대조 도구로 믿지 마라 —
"검사기가 통과했으니 계약이 같다" 는 오늘 우리가 잡아 온 **침묵의 한 종류**다.

사용법: python3 scripts/contract_audit.py [--ai <AI- 경로>]
사본이 없으면 건너뛴다(0 종료) — 이 레포만 받은 사람에게 실패로 보이면 안 된다.
"""
import argparse, pathlib, sys

MIN_SECTION_CHARS = 200

# (이름, 원본에도 사본에도 있어야 하는 표지)
#
# **이름을 표지보다 크게 짓지 마라.** 이 목록의 이름이 곧 출력에 찍히는 문장이고, 사람은 그 이름을 보고
# "그 값이 지켜진다" 고 읽는다. 표지가 낱말 하나면 이름도 "…항목 존재" 여야 한다.
# 2026-09-20 에 `쿨다운 주체`·`허용 오차 0` 두 이름이 그 선을 넘어 있었다 — 주체가 뒤집히고
# 오차 규칙이 100원으로 바뀌어도 `O` 가 찍혔다. **자동화가 안 하는 일을 이름이 한다고 말하면,
# 사람이 봐야 할 자리를 안 보게 된다.**
CHECKS = [
    # Spotify 가 빠져 있었다(2026-09-20). 이름은 "셋" 인데 둘만 보고 있었다 — 이름이 아니라 표지가 틀린 경우다.
    ("요청 serviceName 셋", ["serviceName", "Spotify", "Apple Music", "iCloud+"]),
    ("200 필드", ["sourceUrl", "checkedAt", "sourceHash", "tierName", "billingPeriod", "evidence"]),
    ("502 코드 둘", ["CATALOG-SOURCE-UNAVAILABLE", "CATALOG-SOURCE-CHANGED"]),
    ("URL 요청 미수신 항목 존재", ["요청으로 받지 않는다"]),
    ("쿨다운 항목 존재", ["쿨다운"]),   # 주체(BE)까지는 보지 않는다
    ("허용 오차 항목 존재", ["오차 0"]),   # 값 0 이 지켜지는지는 보지 않는다 — 규칙만 뒤집으면 통과한다
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
