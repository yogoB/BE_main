package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 마스터 읽기 전용 계층. DB 행을 pricing 순수 도메인 객체로 매핑한다.
 * 계산은 하지 않는다 — 그건 pricing 의 몫이다.
 */
@Component
public class CatalogReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final ExchangeRates exchangeRates;

    public CatalogReader(NamedParameterJdbcTemplate jdbc, ExchangeRates exchangeRates) {
        this.jdbc = jdbc;
        this.exchangeRates = exchangeRates;
    }

    /** 요금제 + 통신사 이름. 응답에 통신사명이 필요하지만 MobilePlan 은 통신사를 모르므로 함께 싣는다. */
    public record CandidatePlan(MobilePlan plan, String carrier) {
    }

    // 카탈로그 GET 응답용 뷰. pricing 도메인에 없는 원문 필드(category, quality 등)를 그대로 노출한다.
    public record ServiceView(long id, String name, String category, String officialUrl, List<TierView> tiers) {
    }

    /**
     * 구독 등급 표시용. {@code price}는 {@code currency} 단위의 공식 표기 금액이다.
     * 해외 결제 등급은 {@code krwEstimate}(환율 환산·ESTIMATED)와 기준일을 함께 주고, 원화 등급은 둘 다 null 이다.
     * 환산값은 표시 전용 — 계산에는 쓰지 않는다(D-17).
     */
    public record TierView(long id, String name, long price, String currency,
                           Long krwEstimate, java.time.LocalDate krwRateDate,
                           Integer concurrentStreams, String quality, String note) {
    }

    /** voiceMin·smsCnt 는 공식 표기에 수량이 없으면 null(미확인)이다 — 0(미제공)과 구분한다. */
    public record PlanView(long id, String carrier, String name, String networkType, long basePrice,
                           long dataMb, Long voiceMin, Long smsCnt, Long contractDiscount12m, Long contractDiscount24m) {
    }

    public record BenefitView(long serviceId, String serviceName, Long tierId, String benefitType,
                              java.math.BigDecimal discountValue, boolean exclusive, String exclusiveGroup) {
    }

    /** 구독 서비스 + 소속 티어 목록. 해외 결제 등급에는 원화 환산(표시용)을 붙인다. */
    public List<ServiceView> listServices() {
        // 환율은 배치가 넣어둔 마지막 값만 읽는다 — 요청 경로에서 외부를 부르지 않는다(D-05).
        var usdKrw = exchangeRates.rate("USD", "KRW").orElse(null);
        var tiersByService = new LinkedHashMap<Long, List<TierView>>();
        jdbc.query("""
                SELECT service_id, id, name, price, currency, concurrent_streams, quality, note
                FROM subscription_tier WHERE active AND service_id IN (SELECT id FROM subscription_service WHERE active) ORDER BY service_id, price
                """, new MapSqlParameterSource(), rs -> {
                    String currency = rs.getString("currency");
                    long price = rs.getLong("price");
                    boolean convertible = !"KRW".equals(currency) && usdKrw != null && usdKrw.base().equals(currency);
                    tiersByService.computeIfAbsent(rs.getLong("service_id"), k -> new ArrayList<>())
                            .add(new TierView(rs.getLong("id"), rs.getString("name"), price, currency,
                                    convertible ? ExchangeRates.toKrw(price, usdKrw) : null,
                                    convertible ? usdKrw.rateDate() : null,
                                    rs.getObject("concurrent_streams", Integer.class),
                                    rs.getString("quality"), rs.getString("note")));
                });
        return jdbc.query("""
                SELECT id, name, category, official_url FROM subscription_service WHERE active ORDER BY id
                """, new MapSqlParameterSource(), (rs, i) -> new ServiceView(
                rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getString("official_url"),
                tiersByService.getOrDefault(rs.getLong("id"), List.of())));
    }

    /** 통신 요금제 카탈로그. 시드 적재 전에는 빈 목록. */
    public List<PlanView> listPlans() {
        return jdbc.query("""
                SELECT p.id, c.name AS carrier, p.name, p.network_type, p.base_price,
                       p.data_mb, p.voice_min, p.sms_cnt, p.contract_discount_12m, p.contract_discount_24m
                FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id WHERE p.active ORDER BY p.id
                """, new MapSqlParameterSource(), (rs, i) -> new PlanView(
                rs.getLong("id"), rs.getString("carrier"), rs.getString("name"), rs.getString("network_type"),
                rs.getLong("base_price"), rs.getLong("data_mb"),
                rs.getObject("voice_min", Long.class), rs.getObject("sms_cnt", Long.class),
                rs.getObject("contract_discount_12m", Long.class), rs.getObject("contract_discount_24m", Long.class)));
    }

    /** 요금제별 제휴 혜택. 요금제가 없으면 빈 Optional (호출부가 404 로 변환). */
    public java.util.Optional<List<BenefitView>> listBenefits(long planId) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM mobile_plan WHERE id = :id",
                new MapSqlParameterSource("id", planId), Integer.class);
        if (exists == null || exists == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(jdbc.query("""
                SELECT b.service_id, s.name AS service_name, b.tier_id, b.benefit_type,
                       b.discount_value, b.is_exclusive, b.exclusive_group
                FROM plan_benefit b JOIN subscription_service s ON s.id = b.service_id
                WHERE b.mobile_plan_id = :id ORDER BY b.id
                """, new MapSqlParameterSource("id", planId), (rs, i) -> new BenefitView(
                rs.getLong("service_id"), rs.getString("service_name"), rs.getObject("tier_id", Long.class),
                rs.getString("benefit_type"), rs.getBigDecimal("discount_value"),
                rs.getBoolean("is_exclusive"), rs.getString("exclusive_group"))));
    }

    /** 데이터 요구량을 만족하는 후보 요금제. networkType 은 있으면 필터, 없으면 전체. */
    public List<CandidatePlan> findCandidatePlans(long dataMb, String networkType) {
        var params = new MapSqlParameterSource()
                .addValue("dataMb", dataMb)
                .addValue("networkType", networkType);
        var plans = jdbc.query("""
                SELECT p.id, p.name, p.base_price, p.contract_discount_12m, p.contract_discount_24m, c.name AS carrier
                FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.active AND p.data_mb >= :dataMb
                  AND (:networkType::text IS NULL OR p.network_type = :networkType)
                """, params, (rs, i) -> new Object[]{
                    rs.getLong("id"), rs.getString("name"), rs.getLong("base_price"),
                    contractDiscount(rs.getObject("contract_discount_24m", Long.class),
                            rs.getObject("contract_discount_12m", Long.class)),
                    rs.getString("carrier")});
        if (plans.isEmpty()) {
            return List.of();
        }

        var benefitsByPlan = loadBenefits(plans.stream().map(p -> (Long) p[0]).toList());
        var result = new ArrayList<CandidatePlan>(plans.size());
        for (Object[] p : plans) {
            long id = (Long) p[0];
            var plan = new MobilePlan(id, (String) p[1], (Long) p[2], (Long) p[3],
                    benefitsByPlan.getOrDefault(id, List.of()));
            result.add(new CandidatePlan(plan, (String) p[4]));
        }
        return result;
    }

    /** 계산기용 단건 조회. 없으면 빈 Optional (호출부가 404 로 변환). */
    public java.util.Optional<CandidatePlan> findPlanById(long planId) {
        var plans = jdbc.query("""
                SELECT p.id, p.name, p.base_price, p.contract_discount_12m, p.contract_discount_24m, c.name AS carrier
                FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.id = :id
                """, new MapSqlParameterSource("id", planId), (rs, i) -> new Object[]{
                    rs.getLong("id"), rs.getString("name"), rs.getLong("base_price"),
                    contractDiscount(rs.getObject("contract_discount_24m", Long.class),
                            rs.getObject("contract_discount_12m", Long.class)),
                    rs.getString("carrier")});
        if (plans.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object[] p = plans.get(0);
        long id = (Long) p[0];
        var plan = new MobilePlan(id, (String) p[1], (Long) p[2], (Long) p[3],
                loadBenefits(List.of(id)).getOrDefault(id, List.of()));
        return java.util.Optional.of(new CandidatePlan(plan, (String) p[4]));
    }

    /**
     * 지정한 ID 의 티어들 (계산기 — 특정 조합). 존재하는 것만 반환하므로 호출부가 누락을 검증한다.
     * **원화 확정 가격만** 반환한다 — 해외 표기 금액을 원으로 섞으면 $20 이 20원이 된다.
     */
    public List<SubscriptionTier> findTiersByIds(List<Long> tierIds) {
        if (tierIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, service_id, name, price FROM subscription_tier
                WHERE id IN (:ids) AND currency = 'KRW'
                """, new MapSqlParameterSource("ids", tierIds),
                (rs, i) -> new SubscriptionTier(rs.getLong("id"), rs.getLong("service_id"),
                        rs.getString("name"), rs.getLong("price")));
    }

    /** 요청한 등급 중 해외 결제라 계산에 쓸 수 없는 것 (등급 ID → "서비스명 등급명"). */
    public Map<Long, String> findForeignPricedTiers(List<Long> tierIds) {
        if (tierIds.isEmpty()) {
            return Map.of();
        }
        var found = new LinkedHashMap<Long, String>();
        jdbc.query("""
                SELECT t.id, s.name AS service_name, t.name AS tier_name
                FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                WHERE t.id IN (:ids) AND t.currency <> 'KRW' ORDER BY t.id
                """, new MapSqlParameterSource("ids", tierIds),
                rs -> { found.put(rs.getLong("id"), rs.getString("service_name") + " " + rs.getString("tier_name")); });
        return found;
    }

    /**
     * 요청한 서비스 중 **해외 결제라 원화 확정 가격이 없는** 것 (서비스 ID → 이름).
     * 카탈로그 결손(아직 수집 못 한 것)과 구분하려고 따로 본다 — 이건 결손이 아니라 "사용자 확인이 필요한 금액"이다.
     */
    public Map<Long, String> findForeignPricedServices(List<Long> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        var found = new LinkedHashMap<Long, String>();
        jdbc.query("""
                SELECT s.id, s.name FROM subscription_service s
                WHERE s.active AND s.id IN (:ids)
                  AND EXISTS (SELECT 1 FROM subscription_tier t WHERE t.service_id = s.id AND t.active)
                  AND NOT EXISTS (SELECT 1 FROM subscription_tier t
                                  WHERE t.service_id = s.id AND t.active AND t.currency = 'KRW')
                ORDER BY s.id
                """, new MapSqlParameterSource("ids", serviceIds),
                rs -> { found.put(rs.getLong("id"), rs.getString("name")); });
        return found;
    }

    private Map<Long, List<PlanBenefit>> loadBenefits(List<Long> planIds) {
        var byPlan = new LinkedHashMap<Long, List<PlanBenefit>>();
        jdbc.query("""
                SELECT mobile_plan_id, service_id, tier_id, benefit_type, discount_value,
                       is_exclusive, exclusive_group
                FROM plan_benefit WHERE mobile_plan_id IN (:planIds)
                """, new MapSqlParameterSource("planIds", planIds), rs -> {
            var benefit = new PlanBenefit(
                    rs.getLong("service_id"),
                    rs.getObject("tier_id", Long.class),
                    BenefitType.valueOf(rs.getString("benefit_type")),
                    rs.getBigDecimal("discount_value"),
                    rs.getBoolean("is_exclusive"),
                    rs.getString("exclusive_group"));
            byPlan.computeIfAbsent(rs.getLong("mobile_plan_id"), k -> new ArrayList<>()).add(benefit);
        });
        return byPlan;
    }

    /**
     * 원하는 서비스마다 대표 티어 하나를 고른다: `스탠다드` 우선 → 광고형 제외 최저가 → 최저가.
     * 존재하지 않는 서비스 ID 는 결과에서 빠지므로 호출부가 누락을 검증한다.
     * ponytail: 서비스→티어 매핑은 휴리스틱. 사용자가 티어를 직접 고르게 하려면 계약(§3) 변경 필요.
     */
    public List<SubscriptionTier> findRepresentativeTiers(List<Long> serviceIds) {
        var tiers = jdbc.query("""
                SELECT id, service_id, name, price FROM subscription_tier
                WHERE active AND currency = 'KRW' AND service_id IN (:serviceIds)
                  AND service_id IN (SELECT id FROM subscription_service WHERE active)
                """, new MapSqlParameterSource("serviceIds", serviceIds),
                (rs, i) -> new SubscriptionTier(rs.getLong("id"), rs.getLong("service_id"),
                        rs.getString("name"), rs.getLong("price")));

        var byPrice = Comparator.comparingLong(SubscriptionTier::listPrice);
        return tiers.stream()
                .collect(Collectors.groupingBy(SubscriptionTier::serviceId))
                .values().stream()
                .map(group -> group.stream()
                        .filter(t -> t.name().contains("스탠다드") && !t.name().contains("광고")).min(byPrice)
                        .or(() -> group.stream().filter(t -> !t.name().contains("광고")).min(byPrice))
                        .orElseGet(() -> group.stream().min(byPrice).orElseThrow()))
                .toList();
    }

    /** 원하는 티어 집합에 완전히 포함되는 번들만 반환한다 (부분 번들은 적용 불가). */
    public List<BundleProduct> findApplicableBundles(Set<Long> wantedTierIds) {
        if (wantedTierIds.isEmpty()) {
            return List.of();
        }
        var byBundle = new LinkedHashMap<Long, Object[]>();
        var tierIds = new LinkedHashMap<Long, java.util.Set<Long>>();
        jdbc.query("""
                SELECT b.id, b.name, b.price, bi.tier_id
                FROM bundle_product b JOIN bundle_item bi ON bi.bundle_id = b.id WHERE b.active
                """, new MapSqlParameterSource(), rs -> {
            long bundleId = rs.getLong("id");
            byBundle.putIfAbsent(bundleId, new Object[]{rs.getString("name"), rs.getLong("price")});
            tierIds.computeIfAbsent(bundleId, k -> new java.util.HashSet<>()).add(rs.getLong("tier_id"));
        });

        var result = new ArrayList<BundleProduct>();
        for (var entry : byBundle.entrySet()) {
            var ids = tierIds.get(entry.getKey());
            if (wantedTierIds.containsAll(ids)) {
                result.add(new BundleProduct(entry.getKey(), (String) entry.getValue()[0],
                        (Long) entry.getValue()[1], ids));
            }
        }
        return result;
    }

    // ponytail: 약정 기간(12/24개월) 선택은 미모델링 — 요금제 고유 약정할인은 24개월 우선, 없으면 12개월.
    private static long contractDiscount(Long m24, Long m12) {
        if (m24 != null) {
            return m24;
        }
        return m12 != null ? m12 : 0L;
    }
}
