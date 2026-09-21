package com.palsaekjo.yogobi.catalog;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * {@code taxIncluded} 가 false 면 표기가가 세금 별도라는 뜻이고, {@code krwEstimate} 는 부가세 10%를 더한 값이다.
     * 환산값은 표시 전용 — 계산에는 쓰지 않는다(D-17).
     */
    public record TierView(long id, String name, long price, String currency, boolean taxIncluded,
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
                SELECT service_id, id, name, price, currency, tax_included, concurrent_streams, quality, note
                FROM subscription_tier WHERE active AND service_id IN (SELECT id FROM subscription_service WHERE active) ORDER BY service_id, price
                """, new MapSqlParameterSource(), rs -> {
                    String currency = rs.getString("currency");
                    long price = rs.getLong("price");
                    boolean taxIncluded = rs.getBoolean("tax_included");
                    boolean convertible = !"KRW".equals(currency) && usdKrw != null && usdKrw.base().equals(currency);
                    tiersByService.computeIfAbsent(rs.getLong("service_id"), k -> new ArrayList<>())
                            .add(new TierView(rs.getLong("id"), rs.getString("name"), price, currency, taxIncluded,
                                    convertible ? ExchangeRates.toKrw(price, usdKrw, taxIncluded) : null,
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

    /**
     * 누구나 가입할 수 있는 {@code age_limit} 값. 이 둘과 NULL 만 후보가 된다(G-18).
     * <p>{@code 다이렉트} 는 자격 제한이 아니라 <b>온라인 가입 채널</b>이다 — 빼면 요고 계열 44종이 통째로 사라진다.
     * 나머지(키즈·청년·시니어·복지·외국인·군인·태블릿)는 자격이 없으면 가입 자체가 안 되므로
     * 1등으로 보여줘도 사용자는 가입에 실패한다. 절대 원칙 1 과 같은 논리로 후보에서 뺀다.
     * <p>자유 텍스트라 목록을 코드가 들고 있을 수밖에 없다. 시드에 새 값이 생기면 여기 추가한다.
     */
    private static final String OPEN_TO_ALL = "p.age_limit IS NULL OR p.age_limit IN ('ALL', '다이렉트')";

    /**
     * 망 필터(D-58). 통합요금제({@code LTE_5G})는 5G·LTE 어느 쪽을 골라도 후보다 — 한 요금제가 양쪽에서
     * 쓰이기 때문이다. KT 현재 라인업(초이스·베이직·요고)이 그렇고, 이걸 5G 로만 적어 두던 동안
     * <b>LTE 를 고른 사용자에게 KT 후보가 0건</b>이었다. 쓸 수 있는 선택지를 우리가 숨기고 있었다.
     * 3G 를 고른 사람에게는 통합을 넣지 않는다 — 그 요금제는 3G 단말에서 쓰는 것이 아니다.
     *
     * <p>이 규칙은 {@link #currentPlanExclusion} 도 <b>그대로 끼워 써서</b> "지금 요금제가 왜 후보에서
     * 빠졌나" 를 판정한다(D-61). 사본이 아니라 같은 문자열이라 여기를 고치면 그쪽도 같이 따라온다 —
     * 프론트가 이 규칙을 복제해 들고 있던 시절에는 그게 안 돼 화면 문구가 틀렸다(2026-09-20, v90 에서 제거).
     */
    private static final String NETWORK_MATCHES = """
            :networkType::text IS NULL OR p.network_type = :networkType
                OR (p.network_type = 'LTE_5G' AND :networkType::text IN ('FIVE_G', 'LTE'))""";

    /**
     * 계산 내역에 찍히는 <b>등급 표시명</b>을 만든다. {@code subscription_tier.name} 은 "스탠다드"·
     * "프리미엄"처럼 서비스 없이는 읽히지 않는 이름이 대부분이다(130개 중 123개) — 넷플릭스·디즈니+·
     * 티빙·왓챠가 전부 "스탠다드"를 갖고 있어, 둘을 고르면 <b>같은 라벨 두 줄이 금액만 다르게</b> 뜬다.
     * 운영 응답이 실제로 그랬다(2026-09-21 페르소나 통합 점검).
     *
     * <p>규칙은 넷이고, 위에서부터 먼저 맞는 것을 쓴다.
     * <ol>
     *   <li><b>등급명이 서비스명을 통째로 품으면 그대로.</b> "유튜브 프리미엄 라이트" 에 또 붙이면
     *       "유튜브 프리미엄 유튜브 프리미엄 라이트" 가 된다.</li>
     *   <li><b>서비스명 끝과 등급명 앞이 겹치면 접는다.</b> "YouTube Music" + "Music Premium 개인"
     *       → "YouTube Music Premium 개인". 겹친 부분이 등급명 전부면 서비스명만 남는다 —
     *       "카카오 이모티콘 플러스" + "이모티콘 플러스" → "카카오 이모티콘 플러스".</li>
     *   <li><b>등급명이 이미 브랜드를 말하면 그대로.</b> "Google One" + "Google AI Plus 2TB"
     *       → "Google AI Plus 2TB". 앞에 붙이면 "Google" 이 두 번 나온다.</li>
     *   <li>나머지는 앞에 붙인다. "넷플릭스" + "스탠다드" → "넷플릭스 스탠다드".</li>
     * </ol>
     *
     * <p>2·3 은 내레이터 세션이 운영 문장에서 찾아 알려 줬다(2026-09-21) — 전체 포함만 보던
     * 첫 규칙이 <b>낱말만 겹치는 경우</b>를 놓쳐 "카카오 이모티콘 플러스 이모티콘 플러스" 가 나갔다.
     * 문구를 내레이터에서 다듬지 않은 것이 옳다: 거기서 손대면 그게 곧 라벨 문자열 매칭이 된다.
     *
     * <p><b>공개 선택 화면(`/catalog/services`)은 이 이름을 쓰지 않는다</b> — 거기는 서비스 아래에
     * 등급이 묶여 있어 짧은 이름이 맞다. 여기는 한 줄로 떨어지므로 서비스가 같이 있어야 읽힌다.
     * 표시명이 카탈로그 전체에서 겹치지 않는지는 G-61 e 가 지킨다.
     */
    static String tierDisplayName(String service, String tier) {
        if (tier.contains(service)) {
            return tier;
        }
        String[] serviceWords = service.split(" ");
        String[] tierWords = tier.split(" ");
        for (int overlap = Math.min(serviceWords.length, tierWords.length); overlap >= 1; overlap--) {
            if (Arrays.equals(serviceWords, serviceWords.length - overlap, serviceWords.length,
                    tierWords, 0, overlap)) {
                String rest = String.join(" ", Arrays.copyOfRange(tierWords, overlap, tierWords.length));
                return rest.isEmpty() ? service : service + " " + rest;
            }
        }
        return Arrays.asList(serviceWords).contains(tierWords[0]) ? tier : service + " " + tier;
    }

    /** 데이터 요구량을 만족하는 후보 요금제. networkType 은 있으면 필터, 없으면 전체. */
    public List<CandidatePlan> findCandidatePlans(long dataMb, String networkType) {
        var params = new MapSqlParameterSource()
                .addValue("dataMb", dataMb)
                .addValue("networkType", networkType);
        var plans = jdbc.query("""
                SELECT p.id, p.name, p.base_price, p.contract_discount_12m, p.contract_discount_24m,
                       p.promo_months, p.regular_price, p.benefit_price, p.benefit_label,
                       c.name AS carrier
                FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.active AND p.data_mb >= :dataMb
                  AND (%s)
                  AND (%s)
                """.formatted(NETWORK_MATCHES, OPEN_TO_ALL), params, (rs, i) -> new Object[]{
                    rs.getLong("id"), rs.getString("name"), rs.getLong("base_price"),
                    contractDiscount(rs.getObject("contract_discount_24m", Long.class),
                            rs.getObject("contract_discount_12m", Long.class)),
                    rs.getString("carrier"),
                    rs.getObject("promo_months", Integer.class), rs.getObject("regular_price", Long.class),
                    rs.getObject("benefit_price", Long.class), rs.getString("benefit_label")});
        if (plans.isEmpty()) {
            return List.of();
        }

        var benefitsByPlan = loadBenefits(plans.stream().map(p -> (Long) p[0]).toList());
        var result = new ArrayList<CandidatePlan>(plans.size());
        for (Object[] p : plans) {
            long id = (Long) p[0];
            var plan = new MobilePlan(id, (String) p[1], (Long) p[2], (Long) p[3],
                    benefitsByPlan.getOrDefault(id, List.of()), (Integer) p[5], (Long) p[6],
                    (Long) p[7], (String) p[8]);
            result.add(new CandidatePlan(plan, (String) p[4]));
        }
        return result;
    }

    /**
     * 같은 조건에서 <b>자격 제한 때문에</b> 후보에서 빠진 요금제 수(G-18-g).
     * 안내 문구에 실제 숫자를 넣기 위한 것이므로 0 이면 호출부가 안내를 생략한다.
     */
    public int countAgeRestricted(long dataMb, String networkType) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM mobile_plan p
                WHERE p.active AND p.data_mb >= :dataMb
                  AND (%s)
                  AND NOT (%s)
                """.formatted(NETWORK_MATCHES, OPEN_TO_ALL), new MapSqlParameterSource()
                .addValue("dataMb", dataMb).addValue("networkType", networkType), Integer.class);
        return found == null ? 0 : found;
    }

    /**
     * 망을 좁히는 바람에 놓친 <b>더 싼</b> 요금제(2026-09-21). 없으면 빈 Optional 이다.
     *
     * <p>디테일 모드는 "<b>사용 중인</b> 통신망"을 묻고 그 답을 후보 필터로 쓴다. 그런데
     * <b>"지금 5G를 쓴다"와 "5G만 원한다"는 다른 말이다.</b> 무제한을 원한 실제 사용자가 5G라고
     * 답했다가 알뜰폰 LTE 무제한이 통째로 빠져 "지금이 더 싸요"를 받았다 — 망을 안 좁히면
     * 월 2,800원 절감이 나오는 경우였다. 화면 어디에도 그 사실이 없었다.
     *
     * <p>필터를 없애지는 않는다. 사용자가 고른 조건이다. 대신 <b>그 선택이 무엇을 지웠는지</b>를
     * 숫자로 돌려준다 — 가진 것만 말하고 판단은 사용자에게 맡기는 것이 원칙 5-④다.
     *
     * <p>비교는 <b>기본료끼리</b> 한다. 구독 금액은 후보마다 같고 제휴 혜택만 다른데, 혜택까지
     * 계산하려면 후보 탐색을 두 번 돌려야 한다. 그래서 문구도 "기본료가 더 싸다"까지만 말하고
     * 총액을 약속하지 않는다. {@code null} 을 주는 경우가 곧 "넓혀도 더 싼 건 없다"이다.
     */
    public Optional<CheaperOnOtherNetwork> cheaperIfNetworkWidened(long dataMb, String networkType) {
        if (networkType == null) {
            return Optional.empty();   // 안 좁혔으면 놓친 것도 없다
        }
        var params = new MapSqlParameterSource().addValue("dataMb", dataMb).addValue("networkType", networkType);
        Long chosen = jdbc.queryForObject("""
                SELECT min(p.base_price) FROM mobile_plan p
                WHERE p.active AND p.data_mb >= :dataMb AND (%s) AND (%s)
                """.formatted(NETWORK_MATCHES, OPEN_TO_ALL), params, Long.class);
        if (chosen == null) {
            return Optional.empty();
        }
        var excluded = jdbc.queryForList("""
                SELECT count(*) AS n, min(p.base_price) AS cheapest FROM mobile_plan p
                WHERE p.active AND p.data_mb >= :dataMb AND (%s) AND NOT (%s)
                """.formatted(OPEN_TO_ALL, NETWORK_MATCHES), params);
        if (excluded.isEmpty() || excluded.get(0).get("cheapest") == null) {
            return Optional.empty();
        }
        long count = ((Number) excluded.get(0).get("n")).longValue();
        long cheapest = ((Number) excluded.get(0).get("cheapest")).longValue();
        return cheapest < chosen
                ? Optional.of(new CheaperOnOtherNetwork(count, cheapest, chosen))
                : Optional.empty();
    }

    /** 망 필터가 지운 요금제 수와 그중 최저 기본료, 그리고 남은 후보의 최저 기본료. */
    public record CheaperOnOtherNetwork(long excludedCount, long cheapestExcluded, long cheapestKept) { }

    /**
     * 조건을 채우면 <b>지금 1순위보다 싸지는</b> 요금제(2026-09-21). 없으면 빈 Optional 이다.
     *
     * <p>조건부 할인가는 순위에 쓰지 않는다 — 조건 충족 여부를 우리가 모르기 때문이다. 그런데
     * 쓰지 않으면 그 요금제는 <b>기본료로 경쟁해 상위에 못 든다.</b> KB리브모바일이 정확히 그렇다:
     * `LTE 7GB+(밀리의서재)` 는 기본료 22,900원이라 밀리지만 혜택가는 3,900원이다.
     *
     * <p>그래서 순위는 그대로 두고 <b>있다는 사실만</b> 돌려준다. 사용자가 조건을 채울 수 있는지는
     * 사용자만 안다 — 우리가 대신 판단하지 않고, 대신 숨기지도 않는다.
     *
     * <p>비교는 <b>기본료끼리</b>가 아니라 "이 요금제의 혜택가" 대 "후보 중 가장 싼 기본료"다.
     * 구독 금액은 후보마다 같으므로 그 차이가 곧 총액 차이의 하한이다.
     */
    public Optional<BenefitPricedPlan> cheaperIfConditionMet(long dataMb, String networkType) {
        var params = new MapSqlParameterSource().addValue("dataMb", dataMb).addValue("networkType", networkType);
        Long cheapestBase = jdbc.queryForObject("""
                SELECT min(p.base_price) FROM mobile_plan p
                WHERE p.active AND p.data_mb >= :dataMb AND (%s) AND (%s)
                """.formatted(NETWORK_MATCHES, OPEN_TO_ALL), params, Long.class);
        if (cheapestBase == null) {
            return Optional.empty();
        }
        var found = jdbc.query("""
                SELECT c.name AS carrier, p.name, p.base_price, p.benefit_price, p.benefit_label
                  FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                 WHERE p.active AND p.benefit_price IS NOT NULL AND p.data_mb >= :dataMb
                   AND (%s) AND (%s)
                 ORDER BY p.benefit_price LIMIT 1
                """.formatted(NETWORK_MATCHES, OPEN_TO_ALL), params,
                (rs, i) -> new BenefitPricedPlan(rs.getString("carrier"), rs.getString("name"),
                        rs.getLong("base_price"), rs.getLong("benefit_price"), rs.getString("benefit_label")));
        if (found.isEmpty() || found.get(0).benefitPrice() >= cheapestBase) {
            return Optional.empty();   // 조건을 채워도 더 싸지 않으면 할 말이 없다
        }
        return Optional.of(found.get(0));
    }

    /** 조건부 할인가가 붙은 요금제 하나. {@code label} 은 출처가 그 금액을 부르는 이름 그대로다. */
    public record BenefitPricedPlan(String carrier, String name, long basePrice, long benefitPrice, String label) { }

    /** 계산기용 단건 조회. 없으면 빈 Optional (호출부가 404 로 변환). */
    /**
     * 지금 쓰는 요금제가 <b>후보에서 빠진 이유</b>(D-61). 후보였으면 이 값 자체가 없다.
     *
     * <p>{@code reason} 은 {@code INACTIVE · DATA · NETWORK · ELIGIBILITY} 넷 중 하나이고 판정 순서는
     * {@link #findCandidatePlans} 의 WHERE 절과 같다. 나머지는 화면이 문장을 만들 수 있도록
     * <b>비교한 두 값</b>을 그대로 준다 — "지금 1.8GB, 원하시는 건 5GB" 처럼.
     *
     * <p><b>문장은 여기서 만들지 않는다.</b> 사실만 싣고 문구는 화면·내레이터의 몫이다(D-46·D-47).
     * {@code requiredNetwork} 는 사용자가 망을 안 고르면 null 인데, 그때는 망으로 걸릴 일도 없다.
     */
    public record CurrentPlanExclusion(String reason, long planDataMb, long requiredDataMb,
                                       String planNetwork, String requiredNetwork, String ageLimit) {
    }

    /**
     * 후보 조건을 <b>한 요금제에만</b> 그대로 적용해 어느 절에서 걸렸는지 돌려준다.
     * 통과했거나 요금제가 없으면 empty — 화면이 할 말이 없는 경우다.
     *
     * <p><b>규칙을 자바로 옮겨 적지 않는다.</b> {@code NETWORK_MATCHES}·{@code OPEN_TO_ALL} 를 그대로
     * 끼워 넣어 DB 가 판정한다 — 사본을 만들면 후보 질의를 고칠 때 이쪽이 조용히 어긋나고,
     * 그게 프론트에서 실제로 일어난 일이다(2026-09-20: 데이터가 남는데 "데이터가 모자라다"고 적혔다).
     * 원본이 하나여야 거울이 안 생긴다.
     */
    public java.util.Optional<CurrentPlanExclusion> currentPlanExclusion(long planId, long dataMb, String networkType) {
        var params = new MapSqlParameterSource()
                .addValue("id", planId).addValue("dataMb", dataMb).addValue("networkType", networkType);
        return jdbc.query("""
                SELECT CASE WHEN NOT p.active THEN 'INACTIVE'
                            WHEN p.data_mb < :dataMb THEN 'DATA'
                            WHEN NOT (%s) THEN 'NETWORK'
                            WHEN NOT (%s) THEN 'ELIGIBILITY'
                       END AS reason,
                       p.data_mb, p.network_type, p.age_limit
                FROM mobile_plan p WHERE p.id = :id
                """.formatted(NETWORK_MATCHES, OPEN_TO_ALL), params,
                (rs, i) -> new CurrentPlanExclusion(rs.getString("reason"), rs.getLong("data_mb"), dataMb,
                        rs.getString("network_type"), networkType, rs.getString("age_limit")))
                .stream().filter(fit -> fit.reason() != null).findFirst();
    }

    public java.util.Optional<CandidatePlan> findPlanById(long planId) {
        var plans = jdbc.query("""
                SELECT p.id, p.name, p.base_price, p.contract_discount_12m, p.contract_discount_24m,
                       p.promo_months, p.regular_price, p.benefit_price, p.benefit_label,
                       c.name AS carrier
                FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.id = :id
                """, new MapSqlParameterSource("id", planId), (rs, i) -> new Object[]{
                    rs.getLong("id"), rs.getString("name"), rs.getLong("base_price"),
                    contractDiscount(rs.getObject("contract_discount_24m", Long.class),
                            rs.getObject("contract_discount_12m", Long.class)),
                    rs.getString("carrier"),
                    rs.getObject("promo_months", Integer.class), rs.getObject("regular_price", Long.class),
                    rs.getObject("benefit_price", Long.class), rs.getString("benefit_label")});
        if (plans.isEmpty()) {
            return java.util.Optional.empty();
        }
        Object[] p = plans.get(0);
        long id = (Long) p[0];
        var plan = new MobilePlan(id, (String) p[1], (Long) p[2], (Long) p[3],
                loadBenefits(List.of(id)).getOrDefault(id, List.of()), (Integer) p[5], (Long) p[6],
                (Long) p[7], (String) p[8]);
        return java.util.Optional.of(new CandidatePlan(plan, (String) p[4]));
    }

    /**
     * 지정한 ID 의 티어들 (계산기 — 특정 조합). 존재하는 것만 반환하므로 호출부가 누락을 검증한다.
     * **원화 확정 가격만** 반환한다 — 해외 표기 금액을 원으로 섞으면 $20 이 20원이 된다.
     */
    /**
     * 활성 요금제를 가진 통신사인가. 공백·대소문자는 무시한다 — 사용자가 "KT 엠모바일" 이라고 적어도
     * 카탈로그의 "KT엠모바일" 과 같은 것으로 본다(프론트 검색과 같은 규칙).
     *
     * <p>요금제가 하나도 없는 통신사는 <b>모르는 통신사로 친다.</b> 이름만 알고 요금제를 모르면
     * 추천에 쓸 수 없고, 그게 바로 수집이 필요하다는 신호다.
     */
    public boolean carrierExists(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM carrier c JOIN mobile_plan p ON p.carrier_id = c.id AND p.active
                    WHERE lower(replace(c.name, ' ', '')) = lower(replace(:name, ' ', ''))
                )
                """, new MapSqlParameterSource("name", name), Boolean.class));
    }

    public List<SubscriptionTier> findTiersByIds(List<Long> tierIds) {
        if (tierIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT t.id, t.service_id, s.name AS service_name, t.name AS tier_name, t.price
                  FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                 WHERE t.id IN (:ids) AND t.currency = 'KRW'
                """, new MapSqlParameterSource("ids", tierIds),
                (rs, i) -> new SubscriptionTier(rs.getLong("id"), rs.getLong("service_id"),
                        tierDisplayName(rs.getString("service_name"), rs.getString("tier_name")),
                        rs.getLong("price")));
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
                SELECT t.id, t.service_id, s.name AS service_name, t.name AS tier_name, t.price
                  FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                 WHERE t.active AND t.currency = 'KRW' AND t.service_id IN (:serviceIds)
                   AND s.active
                """, new MapSqlParameterSource("serviceIds", serviceIds),
                (rs, i) -> new SubscriptionTier(rs.getLong("id"), rs.getLong("service_id"),
                        tierDisplayName(rs.getString("service_name"), rs.getString("tier_name")),
                        rs.getLong("price")));

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
