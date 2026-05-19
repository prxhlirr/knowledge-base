package com.boyang.search.qa;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QuestionPlanner {
    private static final Pattern ASCII_ENTITY = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]{1,}");
    private static final Pattern CJK_ENTITY_BEFORE_STATUS = Pattern.compile("([\\u4e00-\\u9fa5]{2,6})(拟任|现任|任命|任职|免职|晋升|升任|提拔|提任|候选)");
    private static final Pattern TIME_TERM = Pattern.compile("\\d{4}年|\\d{1,2}月|\\d{1,2}日|最新|当前|现在|目前|曾经|历史|过去");
    private static final String[] LIST_TERMS = {"哪些", "名单", "人员", "目录", "包括", "有什么", "有哪些", "谁"};
    private static final String[] PROCEDURE_TERMS = {"流程", "步骤", "怎么办理", "如何申请", "怎么申请", "办理"};
    private static final String[] CONDITION_TERMS = {"条件", "要求", "需满足", "需要满足", "资格"};
    private static final String[] COUNT_TIME_TERMS = {"多少", "几个", "几", "时间", "期限", "日期", "多久"};
    private static final String[] COMPARISON_TERMS = {"对比", "区别", "差异", "相比"};
    private static final String[] STATUS_TERMS = {"拟任", "现任", "已任命", "任命", "任职", "免职", "晋升", "升任", "提拔", "提任", "候选人"};

    public QuestionPlan plan(String query) {
        String q = query == null ? "" : query.trim();
        QaIntent intent = detectIntent(q);
        List<String> statusTerms = findTerms(q, STATUS_TERMS);
        List<String> timeTerms = findTimeTerms(q);
        List<String> entities = extractEntities(q);
        boolean statusSensitive = !statusTerms.isEmpty();
        boolean requiresMultiDoc = statusSensitive
                || intent == QaIntent.LIST
                || intent == QaIntent.COMPARISON
                || intent == QaIntent.PROCEDURE
                || intent == QaIntent.CONDITION
                || entities.size() > 1;
        return new QuestionPlan(q, intent, entities, statusTerms, timeTerms, requiresMultiDoc, statusSensitive);
    }

    private QaIntent detectIntent(String q) {
        if (q == null || q.trim().isEmpty()) {
            return QaIntent.UNKNOWN;
        }
        if (containsAny(q, LIST_TERMS)) {
            return QaIntent.LIST;
        }
        if (containsAny(q, PROCEDURE_TERMS)) {
            return QaIntent.PROCEDURE;
        }
        if (containsAny(q, CONDITION_TERMS)) {
            return QaIntent.CONDITION;
        }
        if (containsAny(q, COUNT_TIME_TERMS)) {
            return QaIntent.COUNT_OR_TIME;
        }
        if (containsAny(q, COMPARISON_TERMS)) {
            return QaIntent.COMPARISON;
        }
        return QaIntent.FACT;
    }

    private List<String> extractEntities(String q) {
        Set<String> entities = new LinkedHashSet<>();
        Matcher ascii = ASCII_ENTITY.matcher(q == null ? "" : q);
        while (ascii.find()) {
            entities.add(ascii.group());
        }
        Matcher cjk = CJK_ENTITY_BEFORE_STATUS.matcher(q == null ? "" : q);
        while (cjk.find()) {
            entities.add(cjk.group(1));
        }
        return new ArrayList<>(entities);
    }

    private List<String> findTerms(String q, String[] terms) {
        Set<String> found = new LinkedHashSet<>();
        for (String term : terms) {
            if (q != null && q.contains(term)) {
                found.add(term);
            }
        }
        return new ArrayList<>(found);
    }

    private List<String> findTimeTerms(String q) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = TIME_TERM.matcher(q == null ? "" : q);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return new ArrayList<>(found);
    }

    private boolean containsAny(String text, String[] needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
