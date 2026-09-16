"""골든 케이스 ↔ 테스트 연결 감사.

docs/testing.md 의 각 `## G-NN.` 절이 **누가 검증하는지를 적고 있는지**, 그리고 지목한 테스트 파일이
실제로 있는지 확인한다.

2026-09-17 측정: 17개 절이 검증자를 적지 않았고 두 절은 이미 지워진 `DirectSignupTest` 를 가리켰다.
그 상태에서는 "명세는 있는데 테스트가 없다"를 아무도 찾지 못한다 — 실제로 G-15 b(복구 시 세션 폐기)가
그렇게 새어 나갔다. 명세가 맞았고, 코드가 안 지켰고, 강제하는 테스트가 없었다.

**"없음"도 정답이다.** 프론트 로직이거나 미구현이면 그렇게 적으면 된다. 금지하는 것은 침묵이다.

    python3 scripts/golden_audit.py           # 리포트
    python3 scripts/golden_audit.py --check   # 침묵하거나 없는 파일을 가리키면 종료 코드 1
"""
import pathlib
import re
import sys

doc = pathlib.Path("docs/testing.md").read_text(encoding="utf-8")
tests = {p.stem for p in pathlib.Path("src/test/java").rglob("*.java")}

print("%-6s %-40s %s" % ("케이스", "제목", "검증"))
silent, dangling = [], []
for m in re.finditer(r"^## (G-(\d+))\.\s*(.+)$", doc, re.M):
    gid, title = m.group(1), m.group(3).strip()
    end = doc.find("\n## ", m.end())
    body = doc[m.end(): end if end != -1 else len(doc)]
    marker = re.search(r"\*\*검증\*\*:\s*(.+)", body)
    named = sorted(set(re.findall(r"`([A-Za-z0-9]+Test)`", body)))
    missing = [n for n in named if n not in tests]
    retired = "폐기" in title

    if missing:
        dangling.append((gid, missing))
        state = "❌ 없는 파일: " + ", ".join(missing)
    elif marker:
        state = ("✅ " + ", ".join(named)) if named else "⚪ " + marker.group(1).strip()[:44]
    elif retired:
        state = "(폐기)"
    else:
        silent.append(gid)
        state = "❌ 검증자를 적지 않았다"
    print("%-6s %-40s %s" % (gid, title[:38], state))

print()
if silent:
    print("검증자를 적지 않은 케이스:", ", ".join(silent))
if dangling:
    print("없는 테스트를 가리키는 케이스:", ", ".join(f"{g}({','.join(n)})" for g, n in dangling))
if not silent and not dangling:
    print("모든 골든 케이스가 검증자를 밝히고 있고, 지목한 테스트가 전부 존재한다.")
if "--check" in sys.argv and (silent or dangling):
    sys.exit(1)
