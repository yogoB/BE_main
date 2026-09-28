package com.palsaekjo.yogobi.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import java.io.IOException;
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
        return builder -> builder
                .deserializerByType(String.class, new NoNulString())
                .postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                        StreamReadConstraints.builder().maxDocumentLength(MAX_DOCUMENT_CHARS).build()));
    }

    /**
     * NUL 문자는 Postgres text·jsonb 가 거부한다. 저장 단계에서 500 이 나고 쓰기 전체가 롤백됐다(G-86 g) —
     * 제보·저장 결과 등 JSON 입구마다 따로 막는 대신 본문을 읽을 때 한 번 막는다. 실패는 기존 400 처리기로 간다.
     */
    static final class NoNulString extends StringDeserializer {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = super.deserialize(p, ctxt);
            if (value != null && value.indexOf('\0') >= 0) {
                return (String) ctxt.handleWeirdStringValue(String.class, value, "NUL 문자는 받지 않는다");
            }
            return value;
        }
    }
}
