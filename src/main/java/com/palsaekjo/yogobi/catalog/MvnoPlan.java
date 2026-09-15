package com.palsaekjo.yogobi.catalog;

/**
 * 우체국알뜰폰 요금제조회 API(우정사업본부)의 요금제 1건. 값은 응답 원문 문자열 그대로 담고,
 * 숫자 변환·무제한 처리·망 매핑은 {@link MvnoCatalogLoader}가 담당한다(적재 정책을 한 곳에 모음).
 */
public record MvnoPlan(
        String carrier,        // bizName — 알뜰폰 업체명(판매자)
        String networkType,    // telecomGenerationType — 5G/LTE/LTE·3G/3G
        String planName,       // chargeName
        String contractDiv,    // chargeDiv — 무약정 후불/약정 후불/선불
        String baseFee,        // chargeAmount — 월 기본료(원)
        String voice,          // voiceAmount — 기본음성(분)
        String sms,            // messageAmount — 기본문자(건)
        String data            // dataAmount — 기본데이터(MB)
) {
}
