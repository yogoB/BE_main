package com.palsaekjo.yogobi.recommend;

/** 채워지지 않은 선택 입력 안내. 무엇을 더 주면 얼마나 정확해지는지 프론트가 그대로 렌더링한다. */
public record MissingInput(String field, String impact, String howToFind) {
}
