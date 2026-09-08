# 개발 기록과 지식 그래프

## 원본과 출력

- 서비스 명세와 현재 상태: 기존 `AGENTS.md`, `docs/*.md`.
- 개발 과정·검증·다음 작업: `docs/worklog.md`. 세션 종료 때 최신 날짜를 위에 적는다.
- 커밋과 파일 변경: Git. 아직 커밋하지 않은 변경은 별도로 표시한다.
- 출력: 간사 볼트의 `개발/yogoB_BE`. 기존 개발 기록의 자동 구간만 교체하고 이전 수기 기록은 보존한다.
- `yogoB_BE - 자동 추적.md`: 동기화 시점, 브랜치, 현재 변경, 전체 커밋 이력, 그래프 링크.
- `Graphify/`: graphify가 관리하는 노드·커뮤니티 노트와 Canvas. 생성된 파일은 직접 수정하지 않는다.

## 설치 (체크아웃마다 한 번)

Python 3와 graphify가 필요하다. 먼저 에이전트에서 `/graphify .`로 코드와 문서를 함께 분석한다.
graphify는 이미 별도 Python 환경에 설치되어 있을 수 있으므로 그 인터프리터를 지정한다.

```bash
python3 scripts/knowledge.py install \
  --vault-dir '/볼트/개발/yogoB_BE' \
  --python '/graphify가-설치된/python'
python3 scripts/knowledge.py sync
```

실제 경로는 Git의 로컬 설정 `yogobi.vaultDir`, `yogobi.graphifyPython`에만 저장한다.
`core.hooksPath=.githooks`로 커밋·브랜치 전환·merge 후 동기화한다.
기존 Git 훅이 있으면 설치를 중단한다. 기존 훅을 사용하는 프로젝트는 그 훅에서 `sync`를 호출한다.
macOS/Linux용이며, 훅은 외부 API 키나 백그라운드 프로세스 없이 순차 실행한다.

## 평소 작업

1. 구현과 검증을 끝내고 `docs/state.md`, `docs/worklog.md`를 갱신한다.
2. 문서가 바뀌면 에이전트에서 `/graphify --update`로 의미 관계도 갱신한다.
3. `python3 scripts/knowledge.py sync`를 실행한다. 커밋 전에도 현재 작업이 볼트에 기록된다.
4. 커밋 후 훅이 코드 그래프와 Git 변경 이력을 다시 동기화한다.

`graphify update .`는 로컬 구조 분석이다. 문서의 의미 관계를 새로 추론하지 않으므로
`detect_incremental(kind="semantic")`로 대기 파일을 확인해 자동 추적 노트에 표시한다.
API 계약과 같은 문서 내용을 그래프에서 읽을 때는 출처 파일을 함께 확인한다.

```bash
graphify query "CatalogSeedLoader가 시드를 어떻게 적재하는가?"
graphify explain CatalogSeedLoader
```

동기화 실패는 커밋을 취소하지 않는다. 오류를 확인한 뒤 `sync`로 재시도한다.
임시로 훅을 건너뛸 때는 `YOGOBI_SKIP_TRACKING=1 git commit ...`을 사용한다.
해제는 `git config --local --unset core.hooksPath`로 한다. 기존 노트는 삭제하지 않는다.

## 검증

`python3 scripts/test_knowledge.py`는 임시 Git 저장소와 임시 볼트에서 실제 graphify와 훅을 실행한다.
개인 볼트와 실제 레포의 커밋 이력은 변경하지 않는다.
