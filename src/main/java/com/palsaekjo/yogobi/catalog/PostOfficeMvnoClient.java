package com.palsaekjo.yogobi.catalog;

import java.io.StringReader;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 우체국알뜰폰 요금제조회 Open API(우정사업본부, data.go.kr) 어댑터.
 * 엔드포인트: {@code openapi.epost.go.kr/.../getAlddlChargeList}, 요청 파라미터 ServiceKey 하나, 응답 XML(successYN=Y).
 * fail-soft: 키 미설정·호출 실패·successYN≠Y·파싱 오류면 빈 목록. 적재는 {@link MvnoCatalogLoader}.
 */
@Component
public class PostOfficeMvnoClient {
    private static final Logger log = LoggerFactory.getLogger(PostOfficeMvnoClient.class);

    private final RestClient client;
    private final String apiKey;

    public PostOfficeMvnoClient(RestClient.Builder builder,
                                @Value("${POST_OFFICE_MVNO_API_KEY:}") String apiKey,
                                @Value("${POST_OFFICE_MVNO_API_URL:http://openapi.epost.go.kr/postal/retrieveAlddlChargeService/retrieveAlddlChargeService/getAlddlChargeList}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(15)); // 전체 목록 1회 반환이라 여유
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }

    /** 우체국 입점 알뜰폰 요금제 전체를 1회 조회한다. 실패 시 빈 목록(fail-soft). */
    public List<MvnoPlan> fetchAll() {
        if (!enabled()) {
            return List.of();
        }
        try {
            String xml = client.get().uri(uri -> uri.queryParam("ServiceKey", apiKey).build())
                    .retrieve().body(String.class);
            List<MvnoPlan> plans = parse(xml);
            log.info("우체국알뜰폰 조회 — {}건", plans.size());
            return plans;
        } catch (RuntimeException e) {
            log.warn("우체국알뜰폰 조회 실패 — 건너뜀 (fail-soft): {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 응답 XML을 요금제 목록으로 파싱한다. successYN≠Y·오류면 빈 목록. 외부 XML이므로 XXE 차단. */
    static List<MvnoPlan> parse(String xml) {
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
            if (!"Y".equalsIgnoreCase(firstText(doc, "successYN"))) {
                return List.of();
            }
            NodeList items = doc.getElementsByTagName("AlddlCharge");
            var out = new ArrayList<MvnoPlan>();
            for (int i = 0; i < items.getLength(); i++) {
                Element e = (Element) items.item(i);
                out.add(new MvnoPlan(child(e, "bizName"), child(e, "telecomGenerationType"), child(e, "chargeName"),
                        child(e, "chargeDiv"), child(e, "chargeAmount"), child(e, "voiceAmount"),
                        child(e, "messageAmount"), child(e, "dataAmount")));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String firstText(Document doc, String tag) {
        NodeList n = doc.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static String child(Element item, String tag) {
        NodeList n = item.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }
}
