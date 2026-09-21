-- 퇴역한 등급이 이름을 영원히 쥐고 있었다(2026-09-21, 운영 중단 5분의 원인).
--
-- subscription_tier 의 UNIQUE (service_id, name) 이 **비활성 행에도 걸린다.** 합본에서 사라진 행은
-- 지우지 않고 active=FALSE 로만 내리므로(예전 저장 결과가 계속 읽혀야 한다, G-53), 한 번 쓰인 이름은
-- 다시 못 쓴다. 중복 등급 id 16 을 내린 뒤 살아남은 id 19 에 그 이름을 주려다 기동이 실패했다:
--   duplicate key value violates unique constraint "subscription_tier_service_id_name_key"
--   Detail: Key (service_id, name)=(6, 유튜브 프리미엄 라이트) already exists.
--
-- 테스트는 전부 통과했다 — 새 DB 에는 퇴역 행이 존재한 적이 없기 때문이다. 퇴역 행은 운영에만
-- 있는 상태이고, 그 모양을 테스트가 갖지 않으면 이런 건 영영 안 잡힌다.
--
-- 제약을 **활성 행에만** 건다. 뜻이 오히려 정확해진다: 지금 팔리는 등급끼리 이름이 겹치면 안 되는
-- 것이지, 옛날에 있던 이름을 영원히 예약해 둘 이유가 없다. 퇴역 행은 이름을 그대로 간직하므로
-- 예전에 그 id 로 저장한 결과도 같은 이름으로 읽힌다.
ALTER TABLE subscription_tier DROP CONSTRAINT IF EXISTS subscription_tier_service_id_name_key;

CREATE UNIQUE INDEX subscription_tier_active_name_key
    ON subscription_tier (service_id, name) WHERE active;

COMMENT ON INDEX subscription_tier_active_name_key IS
    '활성 등급끼리만 (서비스, 이름)이 유일하다. 퇴역 행은 이름을 간직하되 점유하지 않는다 — V33';
