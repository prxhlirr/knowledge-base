# Keyword Literal Containment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make keyword search return only documents that literally contain every frontend space-split query term in the configured authoritative fields.

**Architecture:** Keep Elasticsearch BM25/doc-search as a bounded candidate generator, then add an authoritative literal-containment guard before keyword results are returned. The guard runs on Java strings, not ES analyzers, so `match`/IK tokenization can no longer make a document qualify by partial token hits alone.

**Tech Stack:** Java 8, Spring Boot 2.3, Elasticsearch Java API Client 8.6, JUnit 5, Maven.

---

## File Structure

- Create `java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java`
  - Pure Java utility for normalizing text and checking whether every required frontend term appears literally in one of the allowed text buckets.
  - No Spring dependency, no ES dependency, easy to unit test.
- Create `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`
  - Fast unit tests for exact containment, full-width normalization, case normalization, and false positives from partial token matches.
- Modify `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`
  - Apply authoritative filtering after candidate documents have been enriched with body chunks.
  - Drop documents whose body/allowed metadata does not literally contain all frontend terms.
  - Attach debug fields explaining accepted/rejected coverage during development.
- Modify `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`
  - Make recall DSL less misleading by separating broad analyzed candidate clauses from literal/phrase boosts.
  - Add source-level guard for doc-search hits where enough fields are already available.
  - Do not rely on this as the final guard.
- Modify `java_service/src/main/resources/application.yml`
  - Add default keyword literal guard configuration.
- Modify `java_service/src/main/resources/application-dev.yml` and `java_service/src/main/resources/application-prod.yml` only if they override `search.keyword.*`.
- Add/modify tests under `java_service/src/test/java/com/boyang/search/pipeline/steps/`
  - Prefer unit-level tests for guard logic; keep ES integration tests optional because they depend on local ES data.

---

## Required Product Semantics

Frontend tokenization remains:

```text
query.trim().split(/[\\s\\u3000]+/)
```

Keyword mode must mean:

```text
For query terms A B C, a returned document must literally contain A, B, and C
in the configured authoritative text fields.
```

Default authoritative field policy:

```text
BODY_REQUIRED
```

Meaning:

```text
content/display_content chunks must literally contain every required term across the same document.
```

Optional compatibility policy:

```text
BODY_OR_METADATA
```

Meaning:

```text
content/display_content/title/source/document_number/keywords/tags may jointly cover the terms.
```

Do not allow this behavior in strict keyword mode:

```text
ES match analyzer hits one sub-token of a frontend term, then the document is returned even though none of the authoritative fields literally contains the frontend term.
```

---

## Task 1: Add Pure Literal Matcher

**Files:**
- Create: `java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java`
- Create: `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`

- [ ] **Step 1: Write the failing tests**

Create `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`:

```java
package com.boyang.search.pipeline.keyword;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeywordLiteralMatcherTest {

    @Test
    void returnsAllTermsThatAreLiterallyPresent() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", "这里包含 行政复议 申请材料 的正文。");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Arrays.asList("行政复议", "申请材料"),
                buckets);

        assertTrue(result.coversAllTerms());
        assertEquals(setOf("行政复议", "申请材料"), result.getMatchedTerms());
        assertTrue(result.getMissingTerms().isEmpty());
        assertEquals(setOf("body"), result.getSourcesByTerm().get("行政复议"));
    }

    @Test
    void rejectsPartialAnalyzerStyleTokenHit() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", "这里只出现 行政 和 材料，但没有完整短语。");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Arrays.asList("行政复议", "申请材料"),
                buckets);

        assertFalse(result.coversAllTerms());
        assertTrue(result.getMissingTerms().contains("行政复议"));
        assertTrue(result.getMissingTerms().contains("申请材料"));
    }

    @Test
    void normalizesFullWidthAsciiAndCase() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", "ＡＢＣ-2026 文件已发布");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Collections.singletonList("abc-2026"),
                buckets);

        assertTrue(result.coversAllTerms());
        assertEquals(setOf("abc-2026"), result.getMatchedTerms());
    }

    @Test
    void ignoresBlankTermsAndBlankBuckets() {
        Map<String, String> buckets = new LinkedHashMap<>();
        buckets.put("body", null);
        buckets.put("title", "任职公示");

        KeywordLiteralMatcher.MatchResult result = KeywordLiteralMatcher.match(
                Arrays.asList("", " ", "任职公示"),
                buckets);

        assertTrue(result.coversAllTerms());
        assertEquals(setOf("任职公示"), result.getMatchedTerms());
    }

    @SafeVarargs
    private static <T> Set<T> setOf(T... values) {
        return new java.util.LinkedHashSet<>(Arrays.asList(values));
    }
}
```

- [ ] **Step 2: Run matcher tests and verify they fail**

Run:

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol: class KeywordLiteralMatcher
```

- [ ] **Step 3: Implement the matcher**

Create `java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java`:

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

    public static MatchResult match(Collection<String> requiredTerms, Map<String, String> textBuckets) {
        List<String> terms = normalizeTerms(requiredTerms);
        Map<String, String> normalizedBuckets = normalizeBuckets(textBuckets);
        Set<String> matched = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Map<String, Set<String>> sourcesByTerm = new LinkedHashMap<>();

        for (String term : terms) {
            String normalizedTerm = normalize(term);
            Set<String> sources = new LinkedHashSet<>();
            for (Map.Entry<String, String> entry : normalizedBuckets.entrySet()) {
                if (entry.getValue().contains(normalizedTerm)) {
                    sources.add(entry.getKey());
                }
            }
            if (sources.isEmpty()) {
                missing.add(term);
            } else {
                matched.add(term);
                sourcesByTerm.put(term, sources);
            }
        }

        return new MatchResult(matched, missing, sourcesByTerm);
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
        return new String(chars)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeTerms(Collection<String> requiredTerms) {
        if (requiredTerms == null || requiredTerms.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : requiredTerms) {
            String term = raw == null ? "" : raw.trim();
            String normalized = normalize(term);
            if (!normalized.isEmpty() && seen.add(normalized)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static Map<String, String> normalizeBuckets(Map<String, String> textBuckets) {
        Map<String, String> buckets = new LinkedHashMap<>();
        if (textBuckets == null) {
            return buckets;
        }
        for (Map.Entry<String, String> entry : textBuckets.entrySet()) {
            String key = entry.getKey() == null ? "unknown" : entry.getKey();
            String value = normalize(entry.getValue());
            if (!value.isEmpty()) {
                buckets.put(key, value);
            }
        }
        return buckets;
    }

    public static final class MatchResult {
        private final Set<String> matchedTerms;
        private final Set<String> missingTerms;
        private final Map<String, Set<String>> sourcesByTerm;

        private MatchResult(Set<String> matchedTerms,
                            Set<String> missingTerms,
                            Map<String, Set<String>> sourcesByTerm) {
            this.matchedTerms = Collections.unmodifiableSet(new LinkedHashSet<>(matchedTerms));
            this.missingTerms = Collections.unmodifiableSet(new LinkedHashSet<>(missingTerms));
            this.sourcesByTerm = copySources(sourcesByTerm);
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

        public Map<String, Set<String>> getSourcesByTerm() {
            return sourcesByTerm;
        }

        private static Map<String, Set<String>> copySources(Map<String, Set<String>> input) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : input.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
```

- [ ] **Step 4: Run matcher tests and verify they pass**

Run:

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

Expected:

```text
Tests run: 4, Failures: 0, Errors: 0
```

- [ ] **Step 5: Commit**

```powershell
git add java_service/src/main/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcher.java java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java
git commit -m "test: add keyword literal matcher"
```

---

## Task 2: Add Keyword Literal Guard Configuration

**Files:**
- Modify: `java_service/src/main/resources/application.yml`
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`

- [ ] **Step 1: Add configuration defaults**

In `java_service/src/main/resources/application.yml`, add under the existing `search:` block or create it if missing:

```yaml
search:
  keyword:
    literal-guard:
      enabled: true
      mode: BODY_REQUIRED
      debug-fields: true
```

Allowed `mode` values:

```text
BODY_REQUIRED
BODY_OR_METADATA
```

- [ ] **Step 2: Add fields to KeywordCoarseEvidenceStep**

In `KeywordCoarseEvidenceStep`, add imports:

```java
import com.boyang.search.pipeline.keyword.KeywordLiteralMatcher;
import org.springframework.beans.factory.annotation.Value;
```

Add fields near existing `@Value` fields:

```java
@Value("${search.keyword.literal-guard.enabled:true}")
private boolean keywordLiteralGuardEnabled;

@Value("${search.keyword.literal-guard.mode:BODY_REQUIRED}")
private String keywordLiteralGuardMode;

@Value("${search.keyword.literal-guard.debug-fields:true}")
private boolean keywordLiteralGuardDebugFields;
```

- [ ] **Step 3: Commit**

```powershell
git add java_service/src/main/resources/application.yml java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java
git commit -m "feat: add keyword literal guard configuration"
```

---

## Task 3: Apply Authoritative Literal Guard in KeywordCoarseEvidenceStep

**Files:**
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStep.java`
- Test: `java_service/src/test/java/com/boyang/search/pipeline/keyword/KeywordLiteralMatcherTest.java`

- [ ] **Step 1: Add guard helper methods**

Add these methods inside `KeywordCoarseEvidenceStep`:

```java
private boolean shouldKeepByLiteralGuard(Map<String, Object> doc, List<String> terms) {
    if (!keywordLiteralGuardEnabled || terms == null || terms.isEmpty()) {
        return true;
    }
    KeywordLiteralMatcher.MatchResult result = buildLiteralMatchResult(doc, terms);
    if (keywordLiteralGuardDebugFields) {
        doc.put("literal_matched_terms", new ArrayList<>(result.getMatchedTerms()));
        doc.put("literal_missing_terms", new ArrayList<>(result.getMissingTerms()));
        doc.put("literal_match_sources", result.getSourcesByTerm());
        doc.put("literal_guard_mode", normalizedLiteralGuardMode());
    }
    return result.coversAllTerms();
}

@SuppressWarnings("unchecked")
private KeywordLiteralMatcher.MatchResult buildLiteralMatchResult(Map<String, Object> doc, List<String> terms) {
    Map<String, String> buckets = new LinkedHashMap<>();
    List<Map<String, Object>> chunks = castChunkList(doc.get("chunks"));
    StringBuilder body = new StringBuilder();
    if (chunks != null) {
        for (Map<String, Object> chunk : chunks) {
            Map<String, Object> source = castMap(chunk.get("_source"));
            append(body, source != null ? source.get("content") : null);
            append(body, source != null ? source.get("display_content") : null);
        }
    }
    buckets.put("body", body.toString());

    if ("BODY_OR_METADATA".equals(normalizedLiteralGuardMode())) {
        Map<String, Object> source = castMap(doc.get("_source"));
        buckets.put("metadata", buildMetadataText(source));
    }
    return KeywordLiteralMatcher.match(terms, buckets);
}

private String normalizedLiteralGuardMode() {
    String mode = keywordLiteralGuardMode == null ? "" : keywordLiteralGuardMode.trim().toUpperCase(java.util.Locale.ROOT);
    if ("BODY_OR_METADATA".equals(mode)) {
        return "BODY_OR_METADATA";
    }
    return "BODY_REQUIRED";
}
```

If `KeywordCoarseEvidenceStep` already has `append(StringBuilder, Object)`, reuse it. If not, add:

```java
private void append(StringBuilder sb, Object obj) {
    if (obj == null) {
        return;
    }
    if (obj instanceof Collection) {
        for (Object item : (Collection<?>) obj) {
            append(sb, item);
        }
        return;
    }
    if (obj instanceof Map) {
        for (Object item : ((Map<?, ?>) obj).values()) {
            append(sb, item);
        }
        return;
    }
    sb.append('\n').append(obj);
}
```

- [ ] **Step 2: Apply guard when building enrichedDocs**

Replace the final enrichment condition in `KeywordCoarseEvidenceStep`:

```java
if ((chunks != null && !chunks.isEmpty()) || coversAllTerms(metadataTermsByDoc.get(docId), terms)) {
    enrichedDocs.add(doc);
}
```

with:

```java
boolean hasEvidence = (chunks != null && !chunks.isEmpty()) || coversAllTerms(metadataTermsByDoc.get(docId), terms);
if (hasEvidence && shouldKeepByLiteralGuard(doc, terms)) {
    enrichedDocs.add(doc);
}
```

- [ ] **Step 3: Make fallback chunks non-authoritative for strict body unless they contain terms**

Check fallback flow. If fallback chunks are fetched without term filters, they must still pass `buildLiteralMatchResult`. No separate code is needed if Step 2 is applied after fallback assignment.

- [ ] **Step 4: Run compilation**

Run:

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
git commit -m "fix: enforce literal containment for keyword documents"
```

---

## Task 4: Reduce False Candidate Qualification in KeywordRecallStrategy

**Files:**
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`

- [ ] **Step 1: Add source-level candidate guard**

In `collectCandidateHits(...)`, after building `docKey`, skip candidate documents that do not literally contain the term in the returned source fields.

Change the overloads so the current term is passed into collection:

```java
collectCandidateHits(item.result(), term, docIdsByTerm.get(term), docSourcesById, docScoresById);
```

Add/update methods:

```java
private void collectCandidateHits(MultiSearchItem<Object> response,
                                  String term,
                                  Set<String> docIds,
                                  Map<String, Map<String, Object>> docSourcesById,
                                  Map<String, Double> docScoresById) {
    if (response == null || response.hits() == null) {
        return;
    }
    collectCandidateHits(response.hits().hits(), term, docIds, docSourcesById, docScoresById);
}

private void collectCandidateHits(List<Hit<Object>> hits,
                                  String term,
                                  Set<String> docIds,
                                  Map<String, Map<String, Object>> docSourcesById,
                                  Map<String, Double> docScoresById) {
    if (hits == null || hits.isEmpty()) {
        return;
    }
    for (Hit<Object> hit : hits) {
        Map<String, Object> source = castMap(hit.source());
        if (!sourceContainsTerm(source, term)) {
            continue;
        }
        String docKey = buildDocKey(hit.id(), source);
        if (docKey.isEmpty()) {
            continue;
        }
        docIds.add(docKey);
        docSourcesById.putIfAbsent(docKey, source);
        double score = hit.score() != null ? hit.score() : 0.0;
        Double oldScore = docScoresById.get(docKey);
        if (oldScore == null || score > oldScore) {
            docScoresById.put(docKey, score);
        }
    }
}
```

Keep the old no-term overload only if other callers still need it.

- [ ] **Step 2: Ensure sourceContainsTerm uses literal matcher normalization**

Replace the existing `normalizeForContains` logic with:

```java
private boolean sourceContainsTerm(Map<String, Object> source, String term) {
    String haystack = KeywordLiteralMatcher.normalize(buildSearchableText(source));
    String needle = KeywordLiteralMatcher.normalize(term);
    return !needle.isEmpty() && haystack.contains(needle);
}
```

Add import:

```java
import com.boyang.search.pipeline.keyword.KeywordLiteralMatcher;
```

- [ ] **Step 3: Keep ES match DSL as candidate generator**

Do not remove all `match` clauses in this task. The authoritative guard in `KeywordCoarseEvidenceStep` is the final contract. This task only prevents obvious doc-search false positives where returned source fields already prove there is no literal containment.

- [ ] **Step 4: Compile**

Run:

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
git commit -m "fix: filter keyword candidates by literal source containment"
```

---

## Task 5: Tighten ES Query Semantics Without Removing Recall Fallback

**Files:**
- Modify: `java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java`

- [ ] **Step 1: Make literal phrase/keyword clauses the scoring leaders**

In `buildTermMatchQuery`, keep `match` clauses but ensure exact/literal clauses have higher boost. The current code already boosts `title.keyword`, `source`, and `document_number`. Add a high-boost `matchPhrase` to `summary` and `section_titles`:

```java
ib.should(s -> s.matchPhrase(mp -> mp.field("summary").query(term).slop(0).boost(5.0f)));
ib.should(s -> s.matchPhrase(mp -> mp.field("section_titles").query(term).slop(0).boost(6.0f)));
```

- [ ] **Step 2: Avoid analyzed field alone for long frontend terms if source cannot prove containment**

Do not implement complex ES-only logic. The Java guard is authoritative. Add a code comment above `buildTermMatchQuery`:

```java
// This query is only a bounded candidate generator. Keyword correctness is enforced
// later by KeywordCoarseEvidenceStep via KeywordLiteralMatcher, because ES match
// analyzers can match partial IK tokens that do not literally contain the frontend term.
```

- [ ] **Step 3: Compile**

Run:

```powershell
cd java_service
mvn -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```powershell
git add java_service/src/main/java/com/boyang/search/pipeline/steps/KeywordRecallStrategy.java
git commit -m "chore: document keyword recall as candidate generation"
```

---

## Task 6: Add Integration Verification for No Literal Containment

**Files:**
- Modify: `java_service/src/test/java/com/boyang/search/pipeline/KeywordSearchBugTest.java`
- Optional create: `java_service/src/test/java/com/boyang/search/pipeline/steps/KeywordCoarseEvidenceStepLiteralGuardTest.java`

- [ ] **Step 1: Add assertion helper to existing integration test**

In `KeywordSearchBugTest`, add:

```java
@SuppressWarnings("unchecked")
private void assertEveryReturnedDocContainsTerms(List<Map<String, Object>> results, String... terms) {
    for (Map<String, Object> doc : results) {
        StringBuilder body = new StringBuilder();
        Object chunksObj = doc.get("chunks");
        if (chunksObj instanceof List) {
            for (Object chunkObj : (List<?>) chunksObj) {
                if (chunkObj instanceof Map) {
                    Object text = ((Map<?, ?>) chunkObj).get("chunk_text");
                    if (text != null) {
                        body.append('\n').append(text);
                    }
                }
            }
        }
        String normalized = com.boyang.search.pipeline.keyword.KeywordLiteralMatcher.normalize(body.toString());
        for (String term : terms) {
            assertTrue(normalized.contains(com.boyang.search.pipeline.keyword.KeywordLiteralMatcher.normalize(term)),
                    "Returned keyword document must contain literal term in visible body chunks: " + term
                            + " doc=" + doc.get("file_name"));
        }
    }
}
```

- [ ] **Step 2: Use helper in keyword tests**

For tests that assert keyword mode returns results, add:

```java
assertEveryReturnedDocContainsTerms(results, "2025");
```

For multi-term cases, add:

```java
assertEveryReturnedDocContainsTerms(results, "行政复议", "申请材料");
```

Only add multi-term integration assertions if the local ES fixture has known matching documents. If fixture data is not stable, keep multi-term checks in the unit tests from Task 1.

- [ ] **Step 3: Run the stable tests**

Run:

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

Expected:

```text
BUILD SUCCESS
```

Run integration test only when ES is available:

```powershell
cd java_service
mvn -Dtest=KeywordSearchBugTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: Commit**

```powershell
git add java_service/src/test/java/com/boyang/search/pipeline/KeywordSearchBugTest.java
git commit -m "test: verify keyword results contain literal query terms"
```

---

## Task 7: Update API/Runbook Documentation

**Files:**
- Modify: `docs/search_100m_chunk_production_optimization_requirements.md`
- Modify: `docs/phase3_search_gray_observability_runbook.md`

- [ ] **Step 1: Document keyword semantics**

Add a section:

```markdown
### Keyword Literal Containment Guard

Keyword mode uses frontend space-split terms. Elasticsearch BM25/doc-search is a candidate generator only. Before returning keyword documents, Java verifies that every required term is literally contained in the configured authoritative fields.

Default:

```yaml
search.keyword.literal-guard.enabled: true
search.keyword.literal-guard.mode: BODY_REQUIRED
```

`BODY_REQUIRED` means returned documents must contain every query term in `content` or `display_content` chunks. `BODY_OR_METADATA` allows title/source/document number/keywords/tags to satisfy terms for compatibility.
```

- [ ] **Step 2: Document rollback**

Add:

```markdown
Rollback:

```yaml
search.keyword.literal-guard.enabled: false
```

This restores pre-fix behavior where analyzed ES matches can qualify keyword documents. Use only for emergency recall recovery because it can return documents that do not literally contain user query terms.
```

- [ ] **Step 3: Commit**

```powershell
git add docs/search_100m_chunk_production_optimization_requirements.md docs/phase3_search_gray_observability_runbook.md
git commit -m "docs: document keyword literal guard"
```

---

## Final Verification

- [ ] Run unit tests:

```powershell
cd java_service
mvn -Dtest=KeywordLiteralMatcherTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] Run Java compilation:

```powershell
cd java_service
mvn -DskipTests compile
```

Expected:

```text
BUILD SUCCESS
```

- [ ] Run integration tests if ES is available:

```powershell
cd java_service
mvn -Dtest=KeywordSearchBugTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] Manual `/search/home` verification:

Request:

```http
POST /api/v1/search/home
Content-Type: application/json
X-Search-AppCode: ADMIN_MASTER_KEY

{
  "queryText": "行政复议 申请材料",
  "pageSize": 10,
  "topK": 5,
  "scene": "home"
}
```

Expected for `keyword` SSE event:

```text
Every returned item has chunks whose combined chunk_text literally contains both "行政复议" and "申请材料" when BODY_REQUIRED is enabled.
```

Expected debug fields when enabled:

```json
{
  "literal_matched_terms": ["行政复议", "申请材料"],
  "literal_missing_terms": [],
  "literal_guard_mode": "BODY_REQUIRED"
}
```

---

## Self-Review

- Spec coverage: The plan addresses the actual root cause: ES analyzed `match` queries currently qualify documents without literal containment. The authoritative fix is Java-side literal guard before return.
- Placeholder scan: No TBD/TODO placeholders remain.
- Type consistency: `KeywordLiteralMatcher.MatchResult` methods used in tasks are defined in Task 1 and reused consistently.
- Risk: `BODY_REQUIRED` can reduce recall for documents whose terms only appear in metadata. This is intended for the user's stated requirement. Use `BODY_OR_METADATA` only if product decides metadata-only keyword hits are acceptable.
