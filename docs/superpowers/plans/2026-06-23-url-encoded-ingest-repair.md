# URL Encoded Ingest Repair Tasks

## Goal

Stop URL-encoded Chinese text from entering searchable Elasticsearch fields, diagnose the depth of historical pollution, and repair old data by class instead of blindly decoding every field.

## Principles

- URL encoding is transport text, not domain/search text.
- Normalize at ingest and write boundaries before data reaches ES.
- Decode conservatively: only decode percent-encoded UTF-8 patterns that improve readable CJK text.
- Preserve literal `+` and legitimate `%` text.
- Repair historical data only after diagnosing whether pollution is in `kb_doc_search`, document metadata, or full content.

## Phase 1: Implemented

- [x] Add Java normalizer: `java_service/src/main/java/com/boyang/search/utils/DocumentTextNormalizer.java`
- [x] Add Java unit tests: `java_service/src/test/java/com/boyang/search/utils/DocumentTextNormalizerTest.java`
- [x] Add Python normalizer: `ai_service/core/normalization/text_normalizer.py`
- [x] Add Python tests: `ai_service/tools_and_tests/test_text_normalizer.py`
- [x] Normalize Java ingest boundaries:
  - `AbstractIngestStrategy`
  - `UrlIngestStrategy`
  - `DatabaseHtmlSyncJobHandler`
  - `SysFileParseLogServiceImpl`
- [x] Normalize Python worker/pipeline/write boundaries:
  - `task_worker.py`
  - `rag_pipeline.py`
  - `doc_indexer.py`
- [x] Add read-only pollution diagnostic script:
  - `ai_service/scripts/diagnose_url_encoded_pollution.py`

## Phase 1 Verification

- [x] `mvn "-Dtest=com.boyang.search.utils.DocumentTextNormalizerTest" "-DforkCount=0" test`
- [x] Direct Python normalizer test invocation
- [x] `python -m py_compile` for modified Python modules and diagnostic script

Note: the default Maven forked test run failed once because the forked JVM could not reserve heap. The focused non-forked test run passed.

## Phase 2: Historical Data Diagnosis

- [ ] Run `python ai_service/scripts/diagnose_url_encoded_pollution.py` against the target ES cluster.
- [ ] Classify pollution:
  - Class A: only `kb_doc_search` polluted. Rebuild `kb_doc_search` from clean source indexes.
  - Class B: document metadata polluted but content clean. Repair metadata, then rebuild doc-search/doc-meta projections.
  - Class C: `content` or `display_content` polluted. Re-ingest or reparse/revectorize affected documents.
- [ ] Save before/after diagnostic JSON for rollback evidence.

## Phase 3: Repair Execution

- [ ] Add dry-run-first repair command for Class B metadata-only documents.
- [ ] Add rebuild command for `kb_doc_search` using canonicalized source fields.
- [ ] Add operator runbook with exact command order, expected metrics, and rollback path.
- [ ] Add CI smoke test for URL-encoded title/source/content samples.

