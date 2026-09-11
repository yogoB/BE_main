# 개인정보 처리방침 · 처리 인벤토리 (V5)

작성 2026-09-11 · 정책 버전 `PrivacyPolicy.VERSION = 2026-09-11`.
코드 단일 출처는 `privacy/PrivacyPolicy.java`이며 `GET /api/v1/privacy-policy`로 공개한다.
**보유기간·동의 항목은 정책값이다. 확정되면 이 문서·`PrivacyPolicy`·처리방침 버전을 함께 올린다.**

## 처리 인벤토리

| 구분 | 항목 | 처리 목적 | 근거 | 보유기간 |
|---|---|---|---|---|
| 계정 | email, password_hash(BCrypt), google_sub | 회원 인증·본인 확인 | 계약 이행 | 탈퇴 시 즉시 파기 |
| 이용 현황 | current_plan_id, user_subscription, payment_record(merchant_raw·amount·paid_at) | 실질 지불 총액 계산·중복 결제 탐지 | 동의·계약 이행 | 결제내역 12개월·탐지결과 6개월 후 파기, 탈퇴 시 즉시 |
| 인증 세션 | auth_session(SHA-256 지문), user_agent | 로그인 유지·세션 관리 | 계약 이행 | 만료 15분·유휴 5분 후 파기 |
| 동의 증빙 | user_consent | 수집·이용 동의 기록 보관 | 법령상 의무 | 탈퇴 시 파기 |

원문 JWT·브라우저 확인값·비밀번호 평문은 저장하지 않는다. DB에는 지문/해시만 둔다.

## 정보주체 권리 — 구현 상태

| 권리 | 구현 | 경로 |
|---|---|---|
| 열람 | ✅ | `GET /api/v1/me`, `GET /api/v1/me/consent` |
| 삭제(탈퇴) | ✅ | `DELETE /api/v1/me` — 개인 데이터 전부 파기(FK cascade), 세션 무효화 |
| 처리정지(동의 철회) | ✅ (선택 항목) | `POST /api/v1/me/consent/marketing {agree:false}` |
| 정정 | 🟡 부분 | 개별 수정 API는 `/me/*` 구현 시 연결 |
| 본인 데이터 내려받기(마이데이터) | ❌ 미구현 | 고지만. 제3자 전송은 전문기관 필요(project.md D-04) |

## 수집·이용 동의

- **필수(ESSENTIAL)**: 가입(`AuthService.signup`·`googleLogin`) 시 현재 정책 버전으로 자동 기록. 계약 이행 근거이므로 철회 불가.
- **선택(MARKETING)**: 기본 미동의(행 없음). `POST /api/v1/me/consent/marketing`로 동의/철회. 철회는 `withdrawn_at` 표시.
- `user_consent`는 `(user_id, item)` UNIQUE. 재동의는 버전·시각 갱신.

## 보유기간 자동 파기

`RetentionService`(@Scheduled 기본 매일 04:00, `yogobi.retention.cron`으로 조정)가 보유기간 초과분을 파기한다:
- payment_record: paid_at 기준 12개월 초과
- detection_result: detected_at 기준 6개월 초과
- 만료된 auth_email_token·auth_session (로그인 없이도 정리)

`purge()`는 유형별 삭제 건수를 반환한다(운영 점검용). 검증: `RetentionServiceTest`(초과분만 파기, 최신 보존).

## 검증

- `PrivacyApiTest`: 처리방침 공개, 가입 시 필수 동의 기록, 마케팅 동의/철회, **탈퇴 시 6개 테이블(app_user·구독·결제·탐지·동의·세션) 전부 파기**·세션 401, CSRF 강제.
- `RetentionServiceTest`: 보유기간 초과 개인데이터·만료 토큰 파기, 최신 데이터 보존.
- 전체 118개 green.

## 한계 · 정책 확정 대기

- 보유기간(결제 12개월·탐지 6개월)과 동의 항목·문구는 **잠정 기본값**이다. 법무·기획 확정 후 갱신.
- 마이데이터 본인 내려받기·정정 세부 API는 `/me/*` 구현 단계에서 연결한다(미구현을 구현으로 표기하지 않는다).
- 실제 파기 스케줄 운영·백업 파기·로그 보존정책은 배포 환경에서 별도 확인이 필요하다.
