# Phase 3 Search Gray Release And Observability Runbook

## Runtime Switches

All switches are fail-open. If `kb_doc_search` is disabled or errors, search falls back to the existing chunk index path.

| Env | Default | Purpose |
| --- | --- | --- |
| `SEARCH_DOC_SEARCH_ENABLED` | `true` | Global switch for document-level retrieval. |
| `SEARCH_DOC_SEARCH_KEYWORD_ENABLED` | `true` | Use `kb_doc_search` for keyword document enumeration. |
| `SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED` | `true` | Use `kb_doc_search` as `/home` hybrid prefilter. |
| `KB_DOC_SEARCH_READ_ALIAS` | `kb_doc_search` | Read alias used by Java search. |
| `SEARCH_DOC_SEARCH_PREFILTER_MAX_CANDIDATES` | `500` | Max document candidates used to restrict chunk recall. |

Rollback order:

1. Set `SEARCH_DOC_SEARCH_HOME_PREFILTER_ENABLED=false`.
2. If keyword search is affected, set `SEARCH_DOC_SEARCH_KEYWORD_ENABLED=false`.
3. If the index or alias is unhealthy, set `SEARCH_DOC_SEARCH_ENABLED=false`.

## Metrics

`SearchContext.timings` and `search_audit_log` now carry:

| Field | Meaning |
| --- | --- |
| `doc_search_enabled` | Whether the optimized path was enabled. |
| `doc_search_keyword_ms` | Keyword document-level enumeration latency. |
| `doc_search_keyword_candidates` | Keyword document candidates. |
| `doc_search_prefilter_ms` | `/home` hybrid prefilter latency. |
| `doc_search_prefilter_candidates` | Prefilter document candidates. |
| `doc_search_prefilter_applied` | Whether chunk recall was narrowed by document candidates. |

Suggested SLO checks:

- `/api/v1/search` keyword P95 <= 800 ms after warmup.
- `/api/v1/search/home` hybrid search event P95 <= 2000 ms before QA generation.
- `doc_search_prefilter_candidates` should usually be below 500.
- `post_filter_denied_count` must remain near 0; non-zero means ACL projection drift.

## Probe

```bash
python ai_service/scripts/search_latency_probe.py \
  --url http://localhost:8080 \
  --endpoint /api/v1/search \
  --mode keyword \
  --query "任职 公示" \
  -n 100 -c 10
```

For `/home`:

```bash
python ai_service/scripts/search_latency_probe.py \
  --url http://localhost:8080 \
  --endpoint /api/v1/search/home \
  --mode hybrid \
  --queries-file queries.txt \
  -n 50 -c 5
```

Run A/B by toggling the env vars above and comparing P50/P95/P99.
