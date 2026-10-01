package com.palsaekjo.yogobi.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * G-89 b. 본문 상한은 JSON 파서(JsonLimits)에만 있었다. 문자열로 받는 결제내역 업로드는 본문 전체를 메모리에 올린 뒤에야
 * 1MB 를 검사해, 로그인한 사람 하나가 수백 MB 본문으로 512MB 머신을 죽일 수 있었다. 모든 본문에 같은 상한을 건다.
 */
class RequestBodyLimitTest {

    private final RequestBodyLimit filter = new RequestBodyLimit();

    @Test
    void aDeclaredOversizedBodyIsRefusedBeforeAnythingReadsIt() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/me/payments/import");
        request.setContent(new byte[RequestBodyLimit.MAX_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("YGB-REQ-001");
        assertThat(chain.getRequest()).isNull();   // 컨트롤러까지 가지 않는다
    }

    @Test
    void anUndeclaredBodyStopsWhenItCrossesTheLimit() throws Exception {
        // chunked 처럼 길이를 밝히지 않은 본문 — 읽는 도중에 끊는다.
        var request = new MockHttpServletRequest("POST", "/api/v1/me/payments/import") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[RequestBodyLimit.MAX_BYTES + 10]);
        var seen = new AtomicReference<HttpServletRequest>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes()).isInstanceOf(IOException.class);
    }

    @Test
    void aNormalBodyPassesUntouched() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/recommendations");
        request.setContent("{\"a\":1}".getBytes());
        var seen = new AtomicReference<HttpServletRequest>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(new String(seen.get().getInputStream().readAllBytes())).isEqualTo("{\"a\":1}");
    }

    /** G-91. 길이를 밝히지 않은 큰 본문도 400("읽지 못했어요")이 아니라 413 이다. */
    @Test
    void anUndeclaredOversizedBodyIsAlso413() {
        var handler = new GlobalExceptionHandler();
        var unreadable = new org.springframework.http.converter.HttpMessageNotReadableException("x",
                new RequestBodyLimit.TooLarge(), new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

        var response = handler.handleUnreadable(unreadable);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }

    /** 리더는 한 요청에 하나다. 알 수 없는 문자셋은 500 이 아니라 읽을 수 없는 요청이다. */
    @Test
    void theReaderIsReusedAndAnUnknownCharsetIsAnIoError() throws Exception {
        var request = new MockHttpServletRequest("POST", "/x");
        request.setContent("abc".getBytes());
        var seen = new AtomicReference<HttpServletRequest>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        assertThat(seen.get().getReader()).isSameAs(seen.get().getReader());

        var odd = new MockHttpServletRequest("POST", "/x");
        odd.setCharacterEncoding("no-such-charset");
        odd.setContent("abc".getBytes());
        filter.doFilter(odd, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        assertThatThrownBy(() -> seen.get().getReader()).isInstanceOf(IOException.class);
    }
}
