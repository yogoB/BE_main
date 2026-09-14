package com.palsaekjo.yogobi.recommend;

import java.io.StringReader;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 스마트초이스 Open API(KTOA) 어댑터 — 한 번 호출에 사용조건별 추천 3건을 받는다(data.md §2). 메인 소스 아님.
 * fail-soft: 키 미설정·호출 실패·resultCode≠100·파싱 오류면 빈 목록. 격자 스윕은 {@link SmartChoiceSweepService} 가 한다.
 */
@Component
public class SmartChoiceClient {
    private static final Logger log = LoggerFactory.getLogger(SmartChoiceClient.class);
    public static final int UNLIMITED = 999999; // data.md §2: 무제한

    private final RestClient client;
    private final String apiKey;

    public SmartChoiceClient(RestClient.Builder builder,
                             @Value("${SMARTCHOICE_API_KEY:}") String apiKey,
                             @Value("${SMARTCHOICE_API_URL:http://api.smartchoice.or.kr/openAPI.xml}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 키가 있어야 호출한다. 없으면 전 경로가 fail-soft로 비활성. */
    public boolean enabled() {
        return !apiKey.isBlank();
    }

    /**
     * 사용조건으로 공식 추천 3건을 받는다. {@code data}는 MB(호출자가 GB→MB 변환), 무제한은 {@link #UNLIMITED}.
     * {@code type}: 3G=2·LTE=3·5G=6, {@code dis}: 무약정 0·12·24. 실패 시 빈 목록(fail-soft).
     */
    public List<SmartChoiceRecommendation> recommend(int dataMb, int voiceMin, int smsCount, int age, int type, int dis) {
        if (!enabled()) {
            return List.of();
        }
        try {
            String xml = client.get().uri(uri -> uri
                            .queryParam("authkey", apiKey)
                            .queryParam("voice", voiceMin).queryParam("data", dataMb).queryParam("sms", smsCount)
                            .queryParam("age", age).queryParam("type", type).queryParam("dis", dis)
                            .build())
                    .retrieve().body(String.class);
            return parse(xml);
        } catch (RuntimeException e) {
            log.warn("스마트초이스 호출 실패 — 건너뜀 (fail-soft): data={}MB type={} dis={}", dataMb, type, dis);
            return List.of();
        }
    }

    /** 응답 XML을 추천 목록으로 파싱한다. resultCode≠100·오류면 빈 목록. 외부 XML이므로 XXE 차단. */
    static List<SmartChoiceRecommendation> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            if (!"100".equals(firstText(doc, "resultCode"))) {
                return List.of();
            }
            NodeList names = doc.getElementsByTagName("v_plan_name");
            NodeList tels = doc.getElementsByTagName("v_tel");
            NodeList prices = doc.getElementsByTagName("v_plan_price");
            NodeList disPrices = doc.getElementsByTagName("v_dis_price");
            NodeList displays = doc.getElementsByTagName("v_display_data");
            NodeList ranks = doc.getElementsByTagName("rn");
            var out = new ArrayList<SmartChoiceRecommendation>();
            for (int i = 0; i < names.getLength(); i++) {
                out.add(new SmartChoiceRecommendation(intAt(ranks, i), textAt(tels, i), textAt(names, i),
                        wonAt(prices, i), wonAt(disPrices, i), textAt(displays, i)));
            }
            out.sort(Comparator.comparingInt(SmartChoiceRecommendation::rank));
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String firstText(Document doc, String tag) {
        NodeList n = doc.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static String textAt(NodeList n, int i) {
        return i < n.getLength() ? n.item(i).getTextContent().trim() : "";
    }

    private static int intAt(NodeList n, int i) {
        String digits = textAt(n, i).replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private static long wonAt(NodeList n, int i) {
        String digits = textAt(n, i).replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }
}
