# 요고비 백엔드 — 산출물 목차

통신 요금제 + 구독 서비스의 **실제 지불 총액**을 계산해 더 싼 조합과 변경 시점을 추천한다.
Java 21 · Spring Boot · PostgreSQL. 프론트(`yogob`)와 내레이터(`yogob-narrator`)는 별도 레포다.

**최종 확인 2026-09-20 · BE v66 · 테스트 322건 0 실패 · 골든 케이스 G-01~G-54.**

---

## 처음 보는 사람이 읽는 순서

| | 문서 | 무엇이 있나 |
|---|---|---|
| 1 | [`architecture.md`](architecture.md) §1 | **시스템 구성도 · 패키지 · 의존 방향** |
| 2 | [`domain.md`](domain.md) | 계산 규칙과 **용어 표**(이름은 여기서 찾는다) |
| 3 | [`erd.md`](erd.md) | **DB ERD** — 테이블 29개, 영역별 다이어그램 |
| 4 | [`BE_API.md`](BE_API.md) | **API 명세서** — 프론트 연동용 정리본 |
| 5 | [`diagrams/index.html`](diagrams/index.html) | **시퀀스 다이어그램 17개**(현행 13 · 폐기 4) |
| 6 | [`testing.md`](testing.md) | **골든 케이스 G-01~G-54** — 이 서비스의 정답지 |

## 주제별

| 주제 | 문서 |
|---|---|
| 계약 원본(§3 API 계약 표) | [`architecture.md`](architecture.md) — **사람이 관리한다** |
| 데이터 수집·검증 | [`data.md`](data.md) · [`catalog-data.md`](catalog-data.md) |
| 인증·보안 | [`auth.md`](auth.md) · [`auth-security.md`](auth-security.md) · [`google-oauth-guide.md`](google-oauth-guide.md) |
| 개인정보 | [`privacy.md`](privacy.md) · [`mydata.md`](mydata.md) |
| 백오피스 | [`backoffice.md`](backoffice.md) |
| 배포 | [`deploy.md`](deploy.md) |
| 결정 이력 (D-01 ~ D-61) | [`project.md`](project.md) |
| 작업 기록 | [`worklog.md`](worklog.md) · [`state.md`](state.md) — **append-only 로그다.** 날짜별로 그때의 사실을 적은 것이라 **옛 줄은 현재 상태가 아니다**. 지금 모양은 위의 여섯 문서가 옳다 |

## 이 서비스가 지키는 다섯 가지

1. **미사용 혜택은 0원.** 사용자가 원하지 않는 제휴 혜택은 계산에도 근거에도 쓰지 않는다.
2. **금액 계산은 `pricing` 모듈만.** 프론트도 내레이터도 숫자를 만들지 않는다.
3. **추천 경로는 하나다.** 모든 화면이 같은 엔드포인트를 부른다. 챗봇은 만들지 않는다(D-44).
4. **모든 금액에 출처를 붙인다.** `OFFICIAL` / `DERIVED` / `USER_PROVIDED` / `ESTIMATED`.
5. **사용성은 취향이 아니라 검수 항목이다.** 모르면 막히는 화면을 만들지 않는다.

## 숫자로 보는 현재

| | |
|---|---|
| 통신 요금제 | 1,706건 (통신사 21곳) |
| 구독 서비스 · 등급 · 번들 | 39 · 130 · 9 |
| 마이그레이션 | V1 ~ V29 (테이블 29개) |
| 테스트 | 322건 · `pricing`·`detection` **분기 커버리지 100% 강제** |
| 골든 케이스 | G-01 ~ G-54 (`python3 scripts/golden_audit.py --check` 가 검증자 누락을 잡는다) |

## 검증 방법

```bash
docker compose up -d
./gradlew check                       # 테스트 + 커버리지 강제
python3 scripts/golden_audit.py --check   # 골든 케이스마다 검증자가 있는지
```
