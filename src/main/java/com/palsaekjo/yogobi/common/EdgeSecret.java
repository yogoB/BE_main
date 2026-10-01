package com.palsaekjo.yogobi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** {@code EDGE_SHARED_SECRET} 을 {@link ClientAddress} 에 넣는다. 비어 있으면 기동마다 경고한다(G-90 a). */
@Component
class EdgeSecret {
    private static final Logger log = LoggerFactory.getLogger(EdgeSecret.class);

    EdgeSecret(@Value("${EDGE_SHARED_SECRET:}") String secret) {
        if (secret.isBlank()) {
            log.warn("EDGE_SHARED_SECRET 미설정 — X-Client-IP 를 검증 없이 믿는다. 직접 호출자가 공개 한도를 우회할 수 있다");
        } else if (secret.length() < 32) {
            throw new IllegalStateException("EDGE_SHARED_SECRET 은 32자 이상이어야 합니다");
        }
        ClientAddress.trustEdgeSecret(secret.strip());
    }
}
