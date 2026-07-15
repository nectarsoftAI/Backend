package com.nectarsoft.meetai.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * keywords 변환 헬퍼 — DB에는 JSON 배열 문자열로 저장하되, API 응답은 항상 List<String>(배열)로 통일한다.
 * (프론트가 keywords.slice().map()으로 렌더링하므로 문자열이 내려가면 크래시)
 */
public final class Keywords {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

    private Keywords() {}

    /** DB에 저장된 JSON 배열 문자열 → List<String> (null/빈값/파싱 실패 시 빈 리스트) */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, LIST);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** LLM 응답의 keywords(Object: List 또는 기타) → List<String> */
    public static List<String> from(Object value) {
        if (value == null) return Collections.emptyList();
        try {
            return MAPPER.convertValue(value, LIST);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** List<String> → DB 저장용 JSON 배열 문자열 */
    public static String toJson(List<String> keywords) {
        if (keywords == null) return null;
        try {
            return MAPPER.writeValueAsString(keywords);
        } catch (Exception e) {
            return null;
        }
    }
}
