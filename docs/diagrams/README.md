# 요고비 구현 흐름 시퀀스 다이어그램

[다이어그램 목차 열기](index.html)

2026-09-15에 실제 Controller → Service → DB/외부 호출을 확인해 15개 흐름으로 정리했다.
기준은 `78e3921` 및 작업 트리의 변경 시점 경계값·빈 구독 조회 보완이다.
작성 중 들어온 회원 구독·탐지·변경 시점·결제 업로드 커밋도 반영했다.
각 그림의 근거 파일과 확인 시점의 SHA-256은 [코드 근거](sources.json)에 기록한다.
시퀀스 검증은 그림의 검증이며, 실제 Google·SMTP·운영 환경 성공을 뜻하지 않는다.

## 기능별 보기

| 그림 | 현재 구현 범위 | 코드 근거 (`src/main/java/com/palsaekjo/yogobi/` 기준) |
|---|---|---|
| [01 추천·직접 계산](01-recommend.html) | 비회원 공개. 추천 후보별 계산·상위 5개, 지정 조합 1회 계산 | `recommend/RecommendationService.java`, `pricing/CostCalculator.java` |
| [02 챗봇](02-chat.html) | 한 발화 파싱 → 같은 추천 서비스 → 고정 문구 설명 | `chat/ChatController.java`, `chat/AiGateway.java`, AI `app/parse.py`, `app/narrate.py` |
| [03 이메일 가입·재설정](03-email.html) | 목적별 10분 단일 사용 확인 링크, 가입 또는 비밀번호 변경 | `user/AuthController.java`, `AuthService.java`, `AuthEmail.java` |
| [04 Google 로그인](04-google.html) | OIDC code 교환·검증 → 내부 회원·세션 발급 | `user/SecurityConfig.java`, `GoogleLogin.java`, `AuthService.java`, `AuthTokens.java` |
| [05 계정 연결](05-link.html) | 자체↔Google 명시 연결, 재확인, 모든 기존 세션 폐기 | `user/GoogleLogin.java`, `AuthService.java`, `AuthTokens.java` |
| [06 로그인·세션](06-sessions.html) | 자체 로그인·회원 조회·개별/현재/전체 세션 폐기 | `user/AuthController.java`, `AuthTokens.java`, `SecurityConfig.java` |
| [07 동의·탈퇴](07-privacy.html) | 처리방침 공개, 본인 동의 변경, 회원 삭제와 CASCADE | `privacy/PrivacyController.java`, `ConsentService.java`, `user/AuthService.java`, V3~V5 |
| [08 보존·파기](08-retention.html) | 명시적 보존 내부 함수와 독립된 정기 파기 | `privacy/PaymentRetentionService.java`, `RetentionService.java`, V6 |
| [09 카탈로그](09-catalog.html) | 앱 시작 CSV 적재, 이후 공개 조회 | `catalog/CatalogSeedLoader.java`, `DevSeedLoader.java`, `CatalogController.java`, `CatalogReader.java` |
| [10 내 구독·현재 요금제](10-member-data.html) | 회원 본인 구독 조회/등록/삭제·현재 요금제 설정 | `subscription/MeSubscriptionController.java`, `UserSubscriptionService.java` |
| [11 중복 탐지](11-detection.html) | 회원 GET 요청마다 재탐지하여 본인 결과 교체 | `detection/DetectionController.java`, `DetectionService.java`, `DuplicateDetector.java` |
| [12 변경 시점](12-switch.html) | 현재·대상 비용을 계산해 회수 개월·상태 반환 | `recommend/SwitchTimingController.java`, `SwitchTimingService.java`, `pricing/SwitchTiming.java` |
| [13 OCR](13-ocr.html) | AI 내부 API만 구현. BE 이미지 수신/사용자 확인 경로는 미연결 | 별도 AI 레포 `app/main.py`, `app/ocr.py` |
| [14 결제 업로드](14-payment-import.html) | 회원 Mock JSON 검증·가맹점 정규화·외부 결제 분석본 저장 | `subscription/MePaymentController.java`, `PaymentImportService.java`, `port/MockMydataProvider.java`, `MerchantNormalizer.java` |
| [15 공식 API 정기 수집](15-catalog-refresh.html) | 우체국 카탈로그와 스마트초이스 시세를 독립된 일정으로 수집 | `catalog/MvnoCatalogLoader.java`, `PostOfficeMvnoClient.java`, `recommend/SmartChoiceSweepService.java`, `SmartChoiceClient.java` |

## 읽는 방법과 현재 제한

- 세로 방향이 실행 순서다. 반복 계산과 가입/재설정 같은 대안 흐름은 화살표의 설명에 명시했다.
  카탈로그의 앱 시작/사용자 조회, 보존 함수/스케줄 실행, 동의 변경/탈퇴는 서로 독립된 실행이다.
- 긴 경로는 그림에서 `/api/v1`을 생략하기도 한다. 화살표를 선택하면 전체 API 경로와 검증·오류 조건을 볼 수 있다.
  일부 내부 호출은 `Controller 경유`, `CatalogReader 포함`처럼 묶어 표시했다. 별도 네트워크 서비스를 뜻하지 않는다.
- 각 HTML은 독립 실행 가능하며 확대·검색·테마 전환·내보내기를 제공한다. 한국어로 작성했으나
  Archify가 지원하는 고정 Viewer 메뉴와 `<html lang>`은 영어다.
- 추천과 챗봇은 **같은 `RecommendationService`를 직접 호출**한다. 챗봇이 추천 HTTP API를 다시 호출하지 않는다.
  금액은 `pricing`에서 계산하고, AI `/narrate`는 LLM 없이 입력 금액을 문장에 삽입한다.
- 추천 계산은 원하는 서비스만 혜택을 반영한다. 번들은 현재 first-fit이며 전역 최적 조합 탐색은 없다.
  저장 시세의 `priceCrossCheck`는 비교 표시이며 계산 금액·추천 순서를 바꾸지 않고 AI 요청에서도 제외한다.
- 중복 탐지의 `wastedAmount`는 현재 `DuplicateDetector`에서 산출한다. 이를 모두 `pricing` 호출로 그리지 않았다.
- 변경 시점은 현재·대상에 같은 활성 티어를 적용하되 **카탈로그 가격**을 사용한다.
  저장된 `monthly_price`를 실제 청구액 기준으로 합산하는 경로가 아니며 약정/가족결합은 null이다.
  현재 응답의 항목별 출처 미포함 등은 후속 백엔드 검토 대상으로 남긴다.
- Google 로그인·SMTP는 설정된 경우의 코드 흐름이다. 실제 계정 승인·메일 도달·운영 HTTPS 확인은 별도다.
  설정 순서는 [OAuth 가이드](../google-oauth-guide.md)를 따른다.
- 외부 구독 `payment_record`는 탈퇴 CASCADE와 분석본 12개월 정책을 유지한다.
  법정 대상은 `PaymentRetentionService.preserve`를 원본 삭제 전에 명시 호출한 사본만 별도 보관한다.
  자동 5년 보존이나 익명화 보장을 뜻하지 않는다. 사본은 한국시간 확정 기한에 파기한다.
- 결제 업로드는 KRW 정수·승인 내역·실제 일시를 검증한 뒤 전부 저장한다. 미인식 가맹점은 null로 남긴다.
  같은 파일 재업로드 중복 방지와 자동 구독 생성은 미구현이다. 실제 금융기관 API 연동이 아니다.
- 우체국·스마트초이스 수집 그림은 설정된 경우의 코드 흐름이다. 실제 성공 응답·데이터 품질을 검증했다는 뜻이 아니다.
  CSV 정기 갱신과 API 실수신의 현재 검토는 [데이터·개인화·수익 모델 검토](../proposals/2026-09-15-service-direction.md)를 따른다.
- 백엔드 `test bootJar` 전체 160개 실패·오류·스킵 0. 변경 시점 음수 입력·회수 개월 범위 초과는 400이며,
  구독이 없는 회원도 통신 요금제 비교를 이용할 수 있다.
- 대화 이력·다중 발화 병합, BE OCR 화면 연결, 종료 예정 알림은 이 그림의 구현 범위에 포함하지 않았다.

## 검증과 재생성

[전체 전달·시각 검증 기록](verification.json)에 각 JSON/HTML의 SHA-256, 검사 결과,
브라우저 측정 결과 및 실제 이미지 검토 범위를 분리해 기록한다.
`*.receipt.json`은 Archify의 원본 전달 기록, `*.visual-check.json`은 브라우저 측정 원본이다.
`*.visual-check.html`은 밝은/어두운 테마 스크린샷 모음이다.

```bash
node ~/.codex/skills/archify/bin/archify.mjs validate sequence docs/diagrams/01-recommend.sequence.json --quality showcase --json
node ~/.codex/skills/archify/bin/archify.mjs deliver sequence docs/diagrams/01-recommend.sequence.json docs/diagrams/01-recommend.html --quality showcase --json
node ~/.codex/skills/archify/bin/archify.mjs visual-check docs/diagrams/01-recommend.html --json
```

JSON 변경 후 다시 validate → deliver → visual-check를 실행하고 실제 화면을 확인한다.
코드가 바뀌면 이 문서의 구현 상태와 코드 근거도 갱신한다. Graphify는 이 Markdown의 의미를 추적하고,
생성 HTML·이미지·검증 JSON의 재분석은 `.graphifyignore`로 제외해 Viewer 코드를 서비스 코드와 혼동하지 않도록 한다.
