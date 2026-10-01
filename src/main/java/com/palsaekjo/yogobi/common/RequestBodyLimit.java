package com.palsaekjo.yogobi.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청 본문의 상한(G-89 b). {@link JsonLimits} 는 JSON 파서에만 걸려, 문자열로 받는 결제내역 업로드는 본문 전체를
 * 메모리에 올린 뒤에야 1MB 를 검사했다 — 수백 MB 한 건이면 512MB 머신이 죽었다. 길이를 밝힌 본문은 읽기 전에 413 으로,
 * 밝히지 않은(chunked) 본문은 읽는 도중 상한에서 끊는다. 정상 요청은 수 KB 다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimit extends OncePerRequestFilter {
    static final int MAX_BYTES = (int) JsonLimits.MAX_DOCUMENT_CHARS;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"code\":\"YGB-REQ-001\",\"message\":\"보내는 내용이 너무 커요. 나눠서 다시 보내 주세요.\",\"field\":null}}");
            return;
        }
        chain.doFilter(new Limited(request), response);
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        Limited(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new Counting(super.getInputStream());
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class Counting extends ServletInputStream {
        private final ServletInputStream in;
        private long read;

        Counting(ServletInputStream in) {
            this.in = in;
        }

        private int count(int n) throws IOException {
            if (n > 0 && (read += n) > MAX_BYTES) throw new IOException("요청 본문이 상한을 넘었다");
            return n;
        }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return count(in.read(buffer, offset, length));
        }

        @Override public boolean isFinished() { return in.isFinished(); }
        @Override public boolean isReady() { return in.isReady(); }
        @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
    }
}
