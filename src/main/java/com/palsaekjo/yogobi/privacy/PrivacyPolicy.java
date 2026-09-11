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

    /** 처리방침·동의 기록의 버전. 항목·보유기간이 바뀌면 올린다. */
    public static final String VERSION = "2026-09-11";
    public static final int PAYMENT_RETENTION_MONTHS = 12;
    public static final int DETECTION_RETENTION_MONTHS = 6;

    public record Item(String category, List<String> fields, String purpose, String legalBasis, String retention) {
    }

    public record Policy(String version, List<Item> items, List<String> dataSubjectRights) {
    }

    public static Policy current() {
        return new Policy(VERSION, List.of(
                new Item("계정", List.of("email", "password_hash(BCrypt 해시)", "google_sub"),
                        "회원 인증·본인 확인", "계약 이행", "탈퇴 시 즉시 파기"),
                new Item("이용 현황",
                        List.of("current_plan_id", "user_subscription", "payment_record(merchant_raw, amount, paid_at)"),
                        "실질 지불 총액 계산·중복 결제 탐지", "정보주체 동의·계약 이행",
                        "결제내역 " + PAYMENT_RETENTION_MONTHS + "개월·탐지결과 " + DETECTION_RETENTION_MONTHS
                                + "개월 후 파기, 탈퇴 시 즉시 파기"),
                new Item("인증 세션", List.of("auth_session(SHA-256 지문)", "user_agent"),
                        "로그인 유지·세션 관리", "계약 이행", "만료 15분·유휴 5분 후 파기"),
                new Item("동의 증빙", List.of("user_consent"),
                        "수집·이용 동의 기록 보관", "법령상 의무", "탈퇴 시 파기")),
                // 정보주체의 권리(고지). 구현: 열람(GET /me)·삭제(DELETE /me)·처리정지(동의 철회 POST /me/consent/marketing).
                List.of("열람", "정정", "삭제(탈퇴)", "처리정지(동의 철회)", "본인 데이터 내려받기(마이데이터, 미구현)"));
    }
}
