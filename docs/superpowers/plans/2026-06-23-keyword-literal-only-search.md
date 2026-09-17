# Keyword Literal-Only Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make keyword search use only frontend space-split literal terms, with ES analyzed match disabled for keyword mode.

**Architecture:** Keyword mode becomes a literal retrieval path: frontend terms are split by spaces, each term is queried through exact/ngram/optional wildcard fields, and returned documents must satisfy all terms. Hybrid/semantic modes keep analyzed BM25/vector behavior.

**Tech Stack:** Java 8, Spring Boot 2.3, Elasticsearch Java API Client 8.6, JUnit 5, Maven.

---

## Target Semantics

Frontend input:

```text
行政复议 申请材料
```

Keyword mode terms:

```text
["行政复议", "申请材料"]
```

Keyword mode rule:

```text
Each returned document must literally match every frontend term.
No IK/analyzed token split is allowed to qualify a keyword result.
```

Do not use these in keyword mode:

```java
match(... analyzer("ik_max_word"))
match(... analyzer("ik_smart"))
match query that can OR-match sub tokens
```

Allowed in keyword mode:

```text
term query on keyword fields
match_phrase on text fields for exact phrase order
match/wildcard on ngram fields that preserve literal substring semantics
wildcard on keyword fields as a temporary compatibility fallback
```

---

## File Structure

- Modify `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`
  - Replace analyzed keyword recall DSL with literal-only query builders.
  - Keep old analyzed DSL behind an explicit emergency rollback switch.
- Modify `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`
  - Ensure evidence chunks are selected only when `content` or `display_content` literally contains frontend terms.
  - Remove metadata-only acceptance for strict keyword mode unless compatibility mode is enabled.
- Create `java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java`
  - Shared normalization and literal containment helper.
- Create `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`
  - Unit tests proving partial analyzer-token matches are rejected.
- Modify `java_service/src/main/resources/application.yml`
  - Add keyword literal-only configuration.
- Modify ES mapping/backfill docs only if existing mappings lack required ngram fields.

---

## Task 1: Add Keyword Literal Configuration

**Files:**
- Modify: `java_service/src/main/resources/application.yml`
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`

- [ ] **Step 1: Add configuration defaults**

Add under `search:` in `application.yml`:

```yaml
search:
  keyword:
    analyzed-match:
      enabled: false
    literal:
      wildcard-fallback-enabled: true
      require-body-match: true
```

Meaning:

```text
analyzed-match.enabled=false
  Keyword mode does not use IK/analyzed match clauses.

literal.wildcard-fallback-enabled=true
  Temporary compatibility fallback for old indexes that do not have ngram fields.

literal.require-body-match=true
  Final keyword evidence must appear in content/display_content, not only metadata.
```

- [ ] **Step 2: Add fields in `KeywordRecallStrategy`**

Add:

```java
@Value("${search.keyword.analyzed-match.enabled:false}")
private boolean keywordAnalyzedMatchEnabled;

@Value("${search.keyword.literal.wildcard-fallback-enabled:true}")
private boolean keywordLiteralWildcardFallbackEnabled;
```

- [ ] **Step 3: Commit**

```powershell
git add java_service/src/main/resources/application.yml java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java
git commit -m "feat: configure literal-only keyword search"
```

---

## Task 2: Add Shared Literal Matcher

**Files:**
- Create: `java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java`
- Create: `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`

- [ ] **Step 1: Create failing tests**

Create `KeywordLiteralMatcherTest.java`:

```java
package com.boyang.search.pipeline.keyword;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordLiteralMatcherTest {

    @Test
    void acceptsLiteralFrontendTerms() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", "本文件包含行政复议申请材料。");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Arrays.asList("行政复议", "申请材料"), buckets);

        assertTrue(result.coversAllTerms());
        assertTrue(result.getMissingTerms().isEmpty());
    }

    @Test
    void rejectsPartialIkTokenStyleMatch() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", "本文件只包含行政和材料，不包含完整短语。");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Arrays.asList("行政复议", "申请材料"), buckets);

        assertFalse(result.coversAllTerms());
        assertTrue(result.getMissingTerms().contains("行政复议"));
        assertTrue(result.getMissingTerms().contains("申请材料"));
    }

    @Test
    void normalizesFullWidthAsciiAndCase() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", "ＡＢＣ-2026 文件");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Arrays.asList("abc-2026"), buckets);

        assertTrue(result.coversAllTerms());
    }
}
```

- [ ] **Step 2: Implement `KeywordLiteralMatcher`**

Create:

```java
package com.boyang.search.pipeline.keyword;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class KeywordLiteralMatcher {

    private KeywordLiteralMatcher() {
    }

    public static MatchResult match(Collection<String> terms, Map<String, String> buckets) {
        List<String> normalizedTerms = cleanTerms(terms);
        Map<String, String> normalizedBuckets = cleanBuckets(buckets);
        Set<String> matched = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();

        for (String term : normalizedTerms) {
            String needle = normalize(term);
            boolean found = false;
            for (String haystack : normalizedBuckets.values()) {
                if (haystack.contains(needle)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                matched.add(term);
            } else {
                missing.add(term);
            }
        }
        return new MatchResult(matched, missing);
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\u3000') {
                chars[i] = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                chars[i] = (char) (c - 0xFEE0);
            }
        }
        return new String(chars).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> cleanTerms(Collection<String> terms) {
        if (terms == null) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String term : terms) {
            String raw = term == null ? "" : term.trim();
            String normalized = normalize(raw);
            if (!normalized.isEmpty() && seen.add(normalized)) {
                cleaned.add(raw);
            }
        }
        return cleaned;
    }

    private static Map<String, String> cleanBuckets(Map<String, String> buckets) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (buckets == null) {
            return cleaned;
        }
        for (Map.Entry<String, String> entry : buckets.entrySet()) {
            String normalized = normalize(entry.getValue());
            if (!normalized.isEmpty()) {
                cleaned.put(entry.getKey(), normalized);
            }
        }
        return cleaned;
    }

    public static final class MatchResult {
        private final Set<String> matchedTerms;
        private final Set<String> missingTerms;

        private MatchResult(Set<String> matchedTerms, Set<String> missingTerms) {
            this.matchedTerms = Collections.unmodifiableSet(new LinkedHashSet<>(matchedTerms));
            this.missingTerms = Collections.unmodifiableSet(new LinkedHashSet<>(missingTerms));
        }

        public boolean coversAllTerms() {
            return missingTerms.isEmpty();
        }

        public Set<String> getMatchedTerms() {
            return matchedTerms;
        }

        public Set<String> getMissingTerms() {
            return missingTerms;
        }
    }
}
```

- [ ] **Step 3: Run tests**

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```powershell
git add java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java
git commit -m "feat: add keyword literal matcher"
```

---

## Task 3: Replace Keyword Recall DSL With Literal-Only Queries

**Files:**
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`

- [ ] **Step 1: Split query builders**

Rename current analyzed builders:

```java
buildTermMatchQuery(...)
buildLegacyTermMatchQuery(...)
```

to:

```java
buildAnalyzedTermMatchQuery(...)
buildLegacyAnalyzedTermMatchQuery(...)
```

Then add literal builders:

```java
private ObjectBuilder<Query> buildLiteralTermMatchQuery(Query.Builder m, String term) {
    return m.bool(ib -> {
        ib.should(s -> s.term(t -> t.field("title.keyword").value(term).boost(20.0f)));
        ib.should(s -> s.term(t -> t.field("source").value(term).boost(18.0f)));
        ib.should(s -> s.term(t -> t.field("document_number").value(term).boost(18.0f)));
        ib.should(s -> s.term(t -> t.field("keywords").value(term).boost(10.0f)));
        ib.should(s -> s.term(t -> t.field("tags").value(term).boost(8.0f)));
        ib.should(s -> s.term(t -> t.field("entities").value(term).boost(8.0f)));
        ib.should(s -> s.matchPhrase(mp -> mp.field("title").query(term).slop(0).boost(12.0f)));
        ib.should(s -> s.matchPhrase(mp -> mp.field("summary").query(term).slop(0).boost(4.0f)));
        ib.should(s -> s.matchPhrase(mp -> mp.field("section_titles").query(term).slop(0).boost(6.0f)));
        ib.should(s -> s.match(mp -> mp.field("title.ngram").query(term).boost(8.0f)));
        ib.should(s -> s.match(mp -> mp.field("source.ngram").query(term).boost(8.0f)));
        ib.should(s -> s.match(mp -> mp.field("document_number.ngram").query(term).boost(8.0f)));
        if (keywordLiteralWildcardFallbackEnabled && shouldUseLeadingWildcard(term)) {
            ib.should(s -> s.wildcard(q -> q.field("title.keyword").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(4.0f)));
            ib.should(s -> s.wildcard(q -> q.field("source").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(4.0f)));
            ib.should(s -> s.wildcard(q -> q.field("document_number").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(4.0f)));
        }
        return ib.minimumShouldMatch("1");
    });
}
```

For legacy chunk index:

```java
private ObjectBuilder<Query> buildLegacyLiteralTermMatchQuery(Query.Builder m, String term) {
    return m.bool(ib -> {
        ib.should(s -> s.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(12.0f)));
        ib.should(s -> s.matchPhrase(mp -> mp.field("display_content").query(term).slop(0).boost(8.0f)));
        ib.should(s -> s.matchPhrase(mp -> mp.field("metadata.title").query(term).slop(0).boost(8.0f)));
        ib.should(s -> s.term(t -> t.field("metadata.source").value(term).boost(12.0f)));
        ib.should(s -> s.term(t -> t.field("metadata.document_number").value(term).boost(12.0f)));
        ib.should(s -> s.term(t -> t.field("keywords").value(term).boost(8.0f)));
        ib.should(s -> s.term(t -> t.field("metadata.tags_kw").value(term).boost(6.0f)));
        if (keywordLiteralWildcardFallbackEnabled && shouldUseLeadingWildcard(term)) {
            ib.should(s -> s.wildcard(q -> q.field("metadata.source").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(4.0f)));
            ib.should(s -> s.wildcard(q -> q.field("metadata.document_number").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(4.0f)));
        }
        return ib.minimumShouldMatch("1");
    });
}
```

- [ ] **Step 2: Wire builders through the rollback switch**

In `buildCandidateDocsRequestItem`:

```java
bool.must(m -> keywordAnalyzedMatchEnabled
        ? buildAnalyzedTermMatchQuery(m, term)
        : buildLiteralTermMatchQuery(m, term));
```

In `buildLegacyCandidateDocsRequestItem`:

```java
bool.must(m -> keywordAnalyzedMatchEnabled
        ? buildLegacyAnalyzedTermMatchQuery(m, term)
        : buildLegacyLiteralTermMatchQuery(m, term));
```

- [ ] **Step 3: Add an explanatory comment**

Above the literal builder:

```java
// Keyword mode is literal. Do not use ES analyzed match here: IK tokenization
// can qualify documents that do not contain the frontend space-split term.
// Hybrid mode owns analyzed BM25 behavior.
```

- [ ] **Step 4: Compile**

```powershell
cd java_service
mvn -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```powershell
git add java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java
git commit -m "fix: use literal-only queries for keyword recall"
```

---

## Task 4: Enforce Body Literal Match During Evidence Selection

**Files:**
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`

- [ ] **Step 1: Add config**

Add:

```java
@Value("${search.keyword.literal.require-body-match:true}")
private boolean keywordLiteralRequireBodyMatch;
```

- [ ] **Step 2: Make matchedTerms use shared normalization**

Replace local normalization in `matchedTerms` with `KeywordLiteralMatcher.normalize`:

```java
private List<String> matchedTerms(Map<String, Object> source, List<String> terms) {
    List<String> matched = new ArrayList<>();
    String haystack = KeywordLiteralMatcher.normalize(buildBodyText(source));
    for (String term : terms) {
        String needle = KeywordLiteralMatcher.normalize(term);
        if (!needle.isEmpty() && haystack.contains(needle)) {
            matched.add(term);
        }
    }
    return matched;
}
```

Add import:

```java
import com.boyang.search.pipeline.keyword.KeywordLiteralMatcher;
```

- [ ] **Step 3: Stop metadata-only documents in strict mode**

Replace:

```java
if ((chunks != null && !chunks.isEmpty()) || coversAllTerms(metadataTermsByDoc.get(docId), terms)) {
    enrichedDocs.add(doc);
}
```

with:

```java
boolean bodyCovered = chunks != null && !chunks.isEmpty() && chunksCoverAllTerms(chunks, terms);
boolean metadataCovered = coversAllTerms(metadataTermsByDoc.get(docId), terms);
if (keywordLiteralRequireBodyMatch) {
    if (bodyCovered) {
        enrichedDocs.add(doc);
    }
} else if (bodyCovered || metadataCovered) {
    enrichedDocs.add(doc);
}
```

Add helper:

```java
private boolean chunksCoverAllTerms(List<Map<String, Object>> chunks, List<String> terms) {
    if (terms == null || terms.isEmpty()) {
        return true;
    }
    Set<String> covered = new LinkedHashSet<>();
    for (Map<String, Object> chunk : chunks) {
        covered.addAll(toStringList(chunk.get("matched_terms")));
    }
    return covered.containsAll(terms);
}
```

- [ ] **Step 4: Compile**

```powershell
cd java_service
mvn -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```powershell
git add java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java
git commit -m "fix: require body literal evidence for keyword results"
```

---

## Task 5: Review Mapping Support For Literal Keyword Search

**Files:**
- Inspect: `ai_service/core/indexing/es_setup.py`
- Inspect: `docs/es-mapping.json`
- Modify if needed: ES mapping/template docs and migration scripts

- [ ] **Step 1: Check available fields**

Confirm these fields exist in `kb_doc_search`:

```text
title.keyword
title.ngram
source
source.ngram
document_number
document_number.ngram
keywords
tags
entities
section_titles
summary
```

Confirm these fields exist in legacy chunk index:

```text
content
display_content
metadata.title
metadata.source
metadata.document_number
keywords
metadata.tags_kw
```

- [ ] **Step 2: Add missing ngram fields only if absent**

If body substring search must be fast and `content.ngram` does not exist, add it in a v2 mapping instead of mutating existing fields:

```json
"content": {
  "type": "text",
  "analyzer": "ik_max_word",
  "search_analyzer": "ik_smart",
  "fields": {
    "ngram": {
      "type": "text",
      "analyzer": "doc_ngram",
      "search_analyzer": "doc_ngram"
    }
  }
}
```

- [ ] **Step 3: Document backfill requirement**

If a new field is added, document:

```text
Create kb_document_v2 / kb_doc_search_v2.
Reindex from old aliases.
Switch read aliases after validation.
```

- [ ] **Step 4: Commit mapping docs/scripts**

```powershell
git add ai_service/core/indexing/es_setup.py docs/es-mapping.json docs/es_index_migration_and_config_guide.md
git commit -m "docs: prepare mapping support for literal keyword search"
```

Skip this commit if inspection proves current fields are sufficient.

---

## Task 6: Add Regression Tests For Keyword Semantics

**Files:**
- Modify: `java_service/src/test/java/com/boyang/search/pipeline/KeywordSearchBugTest.java`
- Test: `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`

- [ ] **Step 1: Add visible-body assertion helper**

In `KeywordSearchBugTest`:

```java
private void assertVisibleChunksContainAllTerms(List<Map<String, Object>> results, String... terms) {
    for (Map<String, Object> doc : results) {
        StringBuilder visible = new StringBuilder();
        Object chunksObj = doc.get("chunks");
        if (chunksObj instanceof List) {
            for (Object chunkObj : (List<?>) chunksObj) {
                if (chunkObj instanceof Map) {
                    Object text = ((Map<?, ?>) chunkObj).get("chunk_text");
                    if (text != null) {
                        visible.append('\n').append(text);
                    }
                }
            }
        }
        String haystack = com.boyang.search.pipeline.keyword.KeywordLiteralMatcher.normalize(visible.toString());
        for (String term : terms) {
            String needle = com.boyang.search.pipeline.keyword.KeywordLiteralMatcher.normalize(term);
            assertTrue(haystack.contains(needle),
                    "keyword result visible chunks must contain term=" + term + " doc=" + doc.get("file_name"));
        }
    }
}
```

- [ ] **Step 2: Add assertions to stable keyword integration cases**

For any stable query fixture:

```java
assertVisibleChunksContainAllTerms(results, "2025");
```

Do not add data-dependent multi-term integration assertions unless local ES fixture is stable.

- [ ] **Step 3: Run unit tests**

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: Run integration only when ES is available**

```powershell
cd java_service
mvn -Dtest=KeywordSearchBugTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```powershell
git add java_service/src/test/java/com/boyang/search/pipeline/KeywordSearchBugTest.java
git commit -m "test: assert keyword results contain literal visible terms"
```

---

## Task 7: Document Operational Behavior And Rollback

**Files:**
- Modify: `docs/phase3_search_gray_observability_runbook.md`
- Modify: `docs/search_100m_chunk_production_optimization_requirements.md`

- [ ] **Step 1: Add behavior note**

Add:

```markdown
### Keyword Literal-Only Mode

Keyword mode uses frontend space-split terms as literal retrieval terms. ES analyzed match is disabled by default for keyword mode because IK tokenization can return documents that do not contain the user-entered term.

Default:

```yaml
search.keyword.analyzed-match.enabled: false
search.keyword.literal.require-body-match: true
```

Hybrid mode remains the analyzed BM25/vector retrieval path.
```

- [ ] **Step 2: Add rollback**

Add:

```markdown
Emergency rollback:

```yaml
search.keyword.analyzed-match.enabled: true
```

This restores previous keyword recall behavior and may return documents that do not literally contain the frontend term. Use only when literal fields are incomplete and recall must be temporarily recovered.
```

- [ ] **Step 3: Commit**

```powershell
git add docs/phase3_search_gray_observability_runbook.md docs/search_100m_chunk_production_optimization_requirements.md
git commit -m "docs: document literal-only keyword mode"
```

---

## Final Verification

- [ ] Compile:

```powershell
cd java_service
mvn -DskipTests compile
```

- [ ] Unit tests:

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

- [ ] Manual `/search/home` keyword verification:

Request:

```json
{
  "queryText": "行政复议 申请材料",
  "pageSize": 10,
  "topK": 5,
  "scene": "home"
}
```

Expected:

```text
keyword event returns only documents whose visible body chunks contain both "行政复议" and "申请材料".
```

- [ ] Verify hybrid unaffected:

```text
Switch Home tab to hybrid. Hybrid can still return analyzed/semantic matches.
```

---

## Self-Review

- This plan directly implements the user's preferred simpler model: disable ES analyzed match for keyword mode.
- The plan preserves a rollback switch for operational safety.
- The final correctness contract is body literal evidence, not display-only explanation.
- No placeholder steps remain.
