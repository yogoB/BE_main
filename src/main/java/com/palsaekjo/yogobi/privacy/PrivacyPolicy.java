package com.palsaekjo.yogobi.privacy;

import java.util.List;

/**
 * 개인정보 처리 인벤토리의 단일 출처. {@code GET /api/v1/privacy-policy} 로 노출하고,
 * 보유기간 상수는 {@link RetentionService} 파기 기준으로 재사용한다.
 * 보유기간·동의 항목은 정책값이다 — 확정 시 이 파일과 처리방침을 함께 갱신한다.
 */
public final class PrivacyPolicy {
    private PrivacyPolicy() {
    }

    /**
     * 처리방침·동의 기록의 버전. 항목·보유기간이 바뀌면 올린다.
     *
     * <p>올리면 기존 회원의 {@code user_consent.policy_version} 이 뒤처진다. 그 상태는
     * {@code GET /api/v1/me/consent} 의 {@code current:false} 로 드러나고,
     * {@code POST /api/v1/me/consent/acknowledge} 로 해소한다({@link ConsentService}).
     * <b>조용히 갱신하지 않는다</b> — 사용자가 바뀐 내용을 본 시점이 기록으로 남아야 한다.
     *
     * <p>2026-09-17: D-34(Google 전용 로그인)로 회원에게서 비밀번호를 받지 않게 되어 "계정" 항목을 고쳤다.
     */
    public static final String VERSION = "2026-09-17";
    public static final String RETENTION_ZONE = "Asia/Seoul";
    public static final int PAYMENT_RETENTION_MONTHS = 12;
    public static final int DETECTION_RETENTION_MONTHS = 6;
    public static final int REPORT_RETENTION_DAYS = 90;

    public record Item(String category, List<String> fields, String purpose, String legalBasis, String retention) {
    }

    public record Policy(String version, List<Item> items, List<String> dataSubjectRights) {
    }

    public static Policy current() {
        return new Policy(VERSION, List.of(
                new Item("계정", List.of("email(Google 제공)", "google_sub", "name", "nickname"),
                        "회원 인증·본인 확인", "계약 이행", "탈퇴 시 즉시 파기"),
                new Item("이용 현황",
                        List.of("current_plan_id", "user_subscription", "payment_record(merchant_raw, amount, paid_at)",
                                "saved_result(저장한 결과 — 계산 요청과 금액 스냅숏)"),
                        // 저장한 결과의 절감액은 랜딩 통계(금액만, 계정당 1건, 5건 미만 비노출)에 쓰인다 — D-53.
                        "실질 지불 총액 계산·중복 결제 탐지", "정보주체 동의·계약 이행",
                        "결제내역 " + PAYMENT_RETENTION_MONTHS + "개월·탐지결과 " + DETECTION_RETENTION_MONTHS
                                + "개월 후 파기, 탈퇴 시 즉시 파기. 법정 보존 의무가 확인된 증빙만 별도 보관"),
                new Item("법정 보존 결제 사본", List.of("거래 식별자", "가맹점·서비스", "금액·결제일·출처", "보존 근거·기산일·만료일"),
                        "확인된 법정 기록 보존 의무 이행", "개별 기록에 확인된 법령 근거",
                        "탈퇴와 무관하게 확정 만료일까지 분리 보관 후 파기. 회원 ID·이메일·인증 정보는 복사하지 않음"),
                new Item("인증 세션", List.of("auth_session(SHA-256 지문)", "user_agent"),
                        "로그인 유지·세션 관리", "계약 이행", "만료 24시간·유휴 2시간 후 파기(D-48)"),
                new Item("동의 증빙", List.of("user_consent"),
                        "수집·이용 동의 기록 보관", "법령상 의무", "탈퇴 시 파기"),
                new Item("정보 오류 제보", List.of("대상 상품·오류 항목·설명·선택 출처 링크"),
                        "제보 확인·카탈로그 정정", "제보자의 자발적 제출",
                        REPORT_RETENTION_DAYS + "일 후 파기. 회원 ID·이메일·원문 IP는 접수 기록에 저장하지 않음")),
                // 정보주체의 권리(고지). 구현: 열람(GET /me)·삭제(DELETE /me)·처리정지(동의 철회 POST /me/consent/marketing).
                List.of("열람", "정정", "삭제(탈퇴)", "처리정지(동의 철회)", "본인 데이터 내려받기(마이데이터, 미구현)"));
    }
}
