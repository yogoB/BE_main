-- 탐지 결과 금액에 출처를 붙인다(절대 원칙 4). 2026-09-17.
--
-- 왜 필요한가: 요금제가 서비스를 BUNDLE_INCLUDED 로 포함하면서 **등급을 밝히지 않으면**
-- 얼마가 낭비인지 확정할 수 없다 — 전액일 수도, 상위 등급 차액만일 수도 있다.
-- 확정할 수 없다고 침묵하는 것보다 "확인해 보세요"가 낫지만, 그 금액이 추정임을 말해야 한다.
--
-- DERIVED   카탈로그 정가·혜택으로 계산한 값
-- ESTIMATED 등급 미상이라 상한(사용자가 내는 금액 전액)으로 잡은 값. **표시 전용**(D-17)
ALTER TABLE detection_result
    ADD COLUMN provenance TEXT NOT NULL DEFAULT 'DERIVED'
        CHECK (provenance IN ('OFFICIAL', 'DERIVED', 'USER_PROVIDED', 'ESTIMATED'));
