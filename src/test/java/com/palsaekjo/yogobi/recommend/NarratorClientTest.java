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
    private NarratorClient client;

    private static final CostResult COST = new CostResult(1, "5G 슬림+", "SKT", 55000, 68500,
            13500, 162000, List.of());

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/narrate", exchange -> {
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

        Narrator.Narration narration = client.narrationFor(COST, List.of(), 127);

        assertThat(narration.message()).isEqualTo("월 55,000원이에요.");
        assertThat(narration.reasons()).containsExactly("넷플릭스가 포함돼 있어요.");
        assertThat(narration.notices()).containsExactly("가족결합 할인 11,000원은 SKT 요금제에만 반영했어요");
    }

    /** G-31 b — 셋 중 둘만 와도 받는다. 안내가 없는 결과가 정상이기 때문이다. */
    @Test
    void acceptsAResponseWithoutNotices() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[]}""";

        assertThat(client.narrationFor(COST, List.of(), 127).notices()).isEmpty();
    }

    /**
     * G-31 c — 계약에 없는 필드는 그대로 폐기한다. 모르는 값을 화면에 흘리지 않겠다는 원래 의도이며,
     * 이 검사 자체는 옳다. 잘못됐던 것은 <b>목록을 갱신하지 않은 쪽</b>이다.
     */
    @Test
    void discardsAResponseWithAnUnknownField() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[],"surprise":"?"}""";

        assertThat(client.narrationFor(COST, List.of(), 127).message()).isNull();
    }

    /**
     * G-31 d — 폐기한 이유에 <b>필드 이름이 적힌다.</b> 이 예외는 삼켜지므로 메시지가 유일한 흔적이고,
     * 그 한 단어가 없어서 오늘 D-46 이 운영에서 죽은 채 배포됐다.
     */
    @Test
    void theReasonNamesTheOffendingField() {
        body = """
                {"message":"월 55,000원이에요.","reasons":[],"surprise":"?"}""";

        assertThatThrownBy(() -> client.narrate(COST, List.of(), 127))
                .isInstanceOf(NarratorClient.Unavailable.class)
                .hasMessageContaining("surprise");
    }
}
