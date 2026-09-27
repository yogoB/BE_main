package com.palsaekjo.yogobi.common;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 본문 상한(G-85 a). 공개 추천은 목록 길이({@code MAX_IDS})를 <b>역직렬화가 끝난 뒤</b> 본다 —
 * 원소 수천만 개짜리 본문 몇 건이면 그 전에 힙이 찬다. 파서 단계에서 끊으면 기존
 * {@code HttpMessageNotReadableException} 처리기로 400 이 나간다. 정상 요청은 수 KB 다.
 */
@Configuration
public class JsonLimits {
    static final long MAX_DOCUMENT_CHARS = 2_000_000;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer documentLengthLimit() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxDocumentLength(MAX_DOCUMENT_CHARS).build()));
    }
}
