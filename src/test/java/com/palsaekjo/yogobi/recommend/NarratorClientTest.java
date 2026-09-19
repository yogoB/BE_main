package com.palsaekjo.yogobi.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 내레이터 응답 계약(G-31). 여기가 비어 있어서 D-46 이 운영에서 통째로 죽은 채 배포됐다 —
 * 내레이터가 `notices` 를 보내기 시작했는데 BE 의 허용 목록에 없어 <b>설명 전체가 폐기</b>됐고,
 * 실패가 {@code Narration.none()} 으로 삼켜져 아무도 몰랐다.
 *
 * <p>스텁 서버를 쓴다. 이 검증의 대상은 "우리가 남의 응답을 어떻게 받아들이는가"라서
 * 서버가 진짜로 HTTP 를 말해야 의미가 있다.
 */
class NarratorClientTest {
    private HttpServer server;
    private String body = "";
    private String received = "";
    private NarratorClient client;

    private static final CostResult COST = new CostResult(1, "5G 슬림+", "SKT", 55000, 68500,
            13500, 162000, List.of());

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/narrate", exchange -> {
            received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        client = new NarratorClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** G-31 a — `notices` 가 실린 응답을 받는다. 이걸 못 받으면 화면 ⓘ 안내가 통째로 빈다(D-46). */
    @Test
    void acceptsNoticesInTheResponse() {
        body = """
                {"message":"월 55,000원이에요.","reasons":["넷플릭스가 포함돼 있어요."],
                 "notices":["가족결합 할인 11,000원은 SKT 요금제에만 반영했어요"]}""";

        Narrator.Narration narration = client.narrationFor(COST, List.of(), 127, null);

        assertThat(narration.message()).isEqualTo("월 55,000원이에요.");
        assertThat(narration.reasons()).containsExactly("넷플릭스가 포함돼 있어요.");
        assertThat(narration.notices()).containsExactly("가족결합 할인 11,000원은 SKT 요금제에만 반영했어요");
    }

    /**
     * 현재 요금제를 알면 금액과 절감액을 <b>둘 다</b> 보낸다 — 내레이터가 두 수를 빼지 않도록(절대 원칙 2).
     * 모르면 두 필드 모두 싣지 않는다 — 내레이터가 정가 기준 문장으로 돌아가는 조건이다.
     */
    @Test
    void sendsCurrentTotalsOnlyWhenKnown() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[]}""";
        var current = new RecommendationResponse.CurrentCost(
                new CostResult(2, "지금 요금제", "KT", 70390, 70390, 0, 0, List.of()), 17100, 205200);

        client.narrationFor(COST, List.of(), 127, current);
        assertThat(received).contains("\"currentMonthlyTotal\":70390").contains("\"currentMonthlySavings\":17100");

        client.narrationFor(COST, List.of(), 127, null);
        assertThat(received).doesNotContain("currentMonthlyTotal").doesNotContain("currentMonthlySavings");
    }

    /**
     * G-49 — <b>나가는 필드 집합을 여기서 고정한다.</b> 내레이터의 요청 모델은 {@code extra="forbid"} 라
     * 계약 밖 필드가 하나만 섞여도 422 이고, 그 실패는 {@link NarratorClient.Unavailable} 로 흡수돼
     * <b>설명이 통째로 사라진 채 서버는 200 을 준다.</b> 이 사고가 세 번 났다
     * ({@code priceCrossCheck}·{@code ageLimit}·{@code USER_PROVIDED}).
     *
     * <p>막는 장치는 {@code NARRATE_FIELDS} 허용목록과 {@code request.retain(...)} 인데,
     * <b>그 둘이 살아 있는지 보는 테스트가 없었다</b>(옛 {@code ChatControllerTest} 가 하던 일인데
     * 그 패키지는 D-44 로 사라졌다). 리팩터링 중 retain 이 빠지거나 DTO 를 통째로 직렬화하는 코드가
     * 생기면 그날로 재발한다 — 기대값을 코드와 같은 방식으로 만들지 않고 <b>이름을 손으로 적는다.</b>
     */
    @Test
    void sendsExactlyTheContractFields() throws Exception {
        body = """
                {"message":"월 55,000원이에요.","reasons":[]}""";
        var cost = COST.withPriceCrossCheck(PriceCrossCheck.Verdict.unverified());

        client.narrationFor(cost, List.of(new MissingInput("hasFamilyBundle", "확인 필요", "마이페이지")), 127, null);

        var sent = new ObjectMapper().readTree(received);
        var fields = new java.util.ArrayList<String>();
        sent.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "planId", "planName", "carrier", "monthlyTotal", "baseline",
                "monthlySavings", "annualSavings", "breakdown", "missingInputs", "candidateCount");
        // CostResult 에 있지만 계약에는 없는 것들. 늘어나면 여기서 먼저 걸린다.
        assertThat(received).doesNotContain("priceCrossCheck").doesNotContain("semiannualSavings");
    }

    /** G-31 b — 셋 중 둘만 와도 받는다. 안내가 없는 결과가 정상이기 때문이다. */
    @Test
    void acceptsAResponseWithoutNotices() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[]}""";

        assertThat(client.narrationFor(COST, List.of(), 127, null).notices()).isEmpty();
    }

    /**
     * G-31 c — 계약에 없는 필드는 그대로 폐기한다. 모르는 값을 화면에 흘리지 않겠다는 원래 의도이며,
     * 이 검사 자체는 옳다. 잘못됐던 것은 <b>목록을 갱신하지 않은 쪽</b>이다.
     */
    @Test
    void discardsAResponseWithAnUnknownField() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[],"surprise":"?"}""";

        assertThat(client.narrationFor(COST, List.of(), 127, null).message()).isNull();
    }

    /**
     * G-31 d — 폐기한 이유에 <b>필드 이름이 적힌다.</b> 이 예외는 삼켜지므로 메시지가 유일한 흔적이고,
     * 그 한 단어가 없어서 오늘 D-46 이 운영에서 죽은 채 배포됐다.
     */
    @Test
    void theReasonNamesTheOffendingField() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[],"surprise":"?"}""";

        assertThatThrownBy(() -> client.narrate(COST, List.of(), 127, null))
                .isInstanceOf(NarratorClient.Unavailable.class)
                .hasMessageContaining("surprise");
    }
}
