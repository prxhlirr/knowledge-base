# ES Mapping ????

## kb_document_public

- docs: `22`, store: `515.9kb`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "search_analyzer": "ik_smart", "type": "text"}` |
| `display_content` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `sparse_vector` | `{"type": "rank_features"}` |
| `acl_tokens` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `metadata` | `{"metadata_fields": {"acl_tokens": "keyword", "doc_version": "integer", "is_latest": "boolean"}, "type": null}` |

## kb_document_official

- docs: `669`, store: `16mb`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "search_analyzer": "ik_smart", "type": "text"}` |
| `display_content` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `sparse_vector` | `{"type": "rank_features"}` |
| `acl_tokens` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `metadata` | `{"metadata_fields": {"acl_tokens": "keyword", "doc_version": "integer", "is_latest": "boolean"}, "type": null}` |

## kb_doc_meta

- docs: `13`, store: `339kb`, shards: `1`, replicas: `1`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `doc_title` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `doc_vector` | `{"type": "float"}` |
| `acl_tokens` | `{"type": "keyword"}` |
| `source_index` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `index_code` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `owner_unit_code` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `visible_unit_codes` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `permission_version` | `{"type": "long"}` |
| `doc_version` | `{"type": "integer"}` |
| `is_latest` | `{"type": "boolean"}` |
| `security_level` | `{"type": "integer"}` |

## kb_qa_pairs_v2

- docs: `444`, store: `9.1mb`, shards: `1`, replicas: `1`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `doc_title` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `question_vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `acl_tokens` | `{"type": "keyword"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `doc_version` | `{"type": "integer"}` |
| `is_latest` | `{"type": "boolean"}` |
| `security_level` | `{"type": "integer"}` |

## kb_document_law

- docs: `0`, store: `139.7kb`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "search_analyzer": "ik_smart", "type": "text"}` |
| `display_content` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `sparse_vector` | `{"type": "rank_features"}` |
| `acl_tokens` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `metadata` | `{"metadata_fields": {"acl_tokens": "keyword", "doc_version": "integer", "index_code": "text", "is_latest": "boolean", "owner_unit_code": "text", "permission_version": "long", "source_index": "text", "visible_unit_codes": "text"}, "type": null}` |

## kb_doc_meta_v2

- docs: `6`, store: `216kb`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `doc_title` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `title` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `doc_vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `acl_tokens` | `{"type": "keyword"}` |
| `source_index` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `index_code` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `owner_unit_code` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `visible_unit_codes` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `permission_version` | `{"type": "long"}` |
| `doc_version` | `{"type": "integer"}` |
| `is_latest` | `{"type": "boolean"}` |
| `security_level` | `{"type": "integer"}` |

## kb_doc_search_v1

- docs: `22`, store: `402.3kb`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `doc_title` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `title` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword", "ngram": "text"}, "search_analyzer": "ik_smart", "type": "text"}` |
| `tags` | `{"type": "keyword"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `acl_tokens` | `{"type": "keyword"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `doc_version` | `{"type": "integer"}` |
| `is_latest` | `{"type": "boolean"}` |
| `publish_time` | `{"type": "date"}` |
| `security_level` | `{"type": "integer"}` |

## kb_document_notice

- docs: `0`, store: `225b`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "search_analyzer": "ik_smart", "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `sparse_vector` | `{"type": "rank_features"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `metadata` | `{"metadata_fields": {"acl_tokens": "keyword", "doc_version": "integer", "is_latest": "boolean"}, "type": null}` |

## kb_document_news

- docs: `16`, store: `394.5kb`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "search_analyzer": "ik_smart", "type": "text"}` |
| `display_content` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `sparse_vector` | `{"type": "rank_features"}` |
| `acl_tokens` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `metadata` | `{"metadata_fields": {"acl_tokens": "keyword", "doc_version": "integer", "index_code": "text", "is_latest": "boolean", "owner_unit_code": "text", "permission_version": "long", "source_index": "text", "visible_unit_codes": "text"}, "type": null}` |

## kb_document_v1

- docs: `0`, store: `225b`, shards: `1`, replicas: `0`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "search_analyzer": "ik_smart", "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `sparse_vector` | `{"type": "rank_features"}` |
| `source_index` | `{"type": "keyword"}` |
| `index_code` | `{"type": "keyword"}` |
| `owner_unit_code` | `{"type": "keyword"}` |
| `visible_unit_codes` | `{"type": "keyword"}` |
| `permission_version` | `{"type": "long"}` |
| `metadata` | `{"metadata_fields": {"acl_tokens": "keyword", "doc_version": "integer", "is_latest": "boolean"}, "type": null}` |

## kb_qa_pairs

- docs: `444`, store: `9.1mb`, shards: `1`, replicas: `1`

| ?? | ??/?? |
| --- | --- |
| `content` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `doc_title` | `{"analyzer": "ik_max_word", "fields": {"keyword": "keyword"}, "type": "text"}` |
| `vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `question_vector` | `{"dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector"}` |
| `acl_tokens` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `source_index` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `index_code` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `owner_unit_code` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `visible_unit_codes` | `{"fields": {"keyword": "keyword"}, "type": "text"}` |
| `permission_version` | `{"type": "long"}` |
| `doc_version` | `{"type": "integer"}` |
| `is_latest` | `{"type": "boolean"}` |
| `security_level` | `{"type": "integer"}` |
