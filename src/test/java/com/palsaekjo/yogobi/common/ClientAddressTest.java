package com.palsaekjo.yogobi.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * G-90 a. BE 가 공개 주소로도 열려 있어 직접 호출자가 {@code X-Client-IP} 를 지어내면 요청마다 새 한도를 받았다 —
 * 공개 계산 경로의 한도가 사실상 없었다. 프론트 nginx 만 아는 비밀 헤더가 함께 올 때만 그 값을 믿는다.
 */
class ClientAddressTest {

    @AfterEach
    void reset() {
        ClientAddress.trustEdgeSecret("");
    }

    private static MockHttpServletRequest request(String clientIp, String edgeAuth) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        if (clientIp != null) request.addHeader("X-Client-IP", clientIp);
        if (edgeAuth != null) request.addHeader(ClientAddress.EDGE_HEADER, edgeAuth);
        return request;
    }

    @Test
    void aForgedClientIpWithoutTheEdgeSecretIsIgnored() {
        ClientAddress.trustEdgeSecret("edge-secret-for-test-0123456789");

        assertThat(ClientAddress.of(request("198.51.100.1", null))).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.of(request("198.51.100.1", "wrong"))).isEqualTo("203.0.113.9");
        assertThat(ClientAddress.forwarded(request("198.51.100.1", "wrong"))).isFalse();
    }

    @Test
    void theEdgeWithTheSecretIsTrusted() {
        ClientAddress.trustEdgeSecret("edge-secret-for-test-0123456789");

        var fromEdge = request("198.51.100.1", "edge-secret-for-test-0123456789");
        assertThat(ClientAddress.of(fromEdge)).isEqualTo("198.51.100.1");
        assertThat(ClientAddress.forwarded(fromEdge)).isTrue();
    }

    /**
     * G-90 a′. IPv6 사용자는 보통 /64 하나를 통째로 받는다. 주소 하나하나를 버킷으로 쓰면 요청마다 주소를 바꿔
     * 새 한도를 받을 수 있었다 — 같은 /64 는 한 버킷이다. IPv4 는 그대로다.
     */
    @Test
    void ipv6AddressesShareTheirSlash64Bucket() {
        var a = new MockHttpServletRequest();
        a.setRemoteAddr("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        var b = new MockHttpServletRequest();
        b.setRemoteAddr("2001:db8:1:2::9");
        var other = new MockHttpServletRequest();
        other.setRemoteAddr("2001:db8:1:3::9");

        assertThat(ClientAddress.of(a)).isEqualTo(ClientAddress.of(b));
        assertThat(ClientAddress.of(a)).isNotEqualTo(ClientAddress.of(other));
        assertThat(ClientAddress.of(request(null, null))).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutAConfiguredSecretTheOldBehaviourStays() {
        // 배포 순서상 잠깐 비어 있을 수 있다 — 그 사이 프론트 사용자 전원이 한 버킷으로 묶이지 않게 한다(기동 시 경고).
        assertThat(ClientAddress.of(request("198.51.100.1", null))).isEqualTo("198.51.100.1");
    }
}
