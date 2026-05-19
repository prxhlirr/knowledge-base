package com.boyang.search.qa;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QaCitationVerifier {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    public Map<String, Object> verify(String answer, List<Map<String, Object>> citations) {
        Set<Integer> validIndexes = new LinkedHashSet<>();
        if (citations != null) {
            for (Map<String, Object> citation : citations) {
                Object index = citation == null ? null : citation.get("index");
                Integer n = toInt(index);
                if (n != null) {
                    validIndexes.add(n);
                }
            }
        }

        Set<Integer> referencedIndexes = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            Integer n = toInt(matcher.group(1));
            if (n != null) {
                referencedIndexes.add(n);
            }
        }

        List<Integer> invalidIndexes = new ArrayList<>();
        for (Integer referenced : referencedIndexes) {
            if (!validIndexes.contains(referenced)) {
                invalidIndexes.add(referenced);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("citation_count", validIndexes.size());
        result.put("referenced_indexes", new ArrayList<>(referencedIndexes));
        result.put("invalid_indexes", invalidIndexes);
        result.put("has_answer_citation", !referencedIndexes.isEmpty());
        result.put("has_invalid_citation", !invalidIndexes.isEmpty());
        result.put("pass", invalidIndexes.isEmpty() && (validIndexes.isEmpty() || !referencedIndexes.isEmpty()));
        return result;
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
