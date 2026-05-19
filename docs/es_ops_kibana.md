# ES 索引运维脚本手册（Kibana Dev Tools 格式）

> 所有脚本可直接粘贴到 **Kibana → Dev Tools → Console** 中执行。
> 光标移到任意命令块内，按 `Ctrl+Enter`（或右侧 ▶ 按钮）单条执行。
>
> **约定**：
> - 主文档索引：`kb_document_v1`（或对应分区索引，如 `kb_document_law`）
> - QA 索引：`kb_qa_pairs`
> - 通配符操作多个索引：`kb_document_*`

---

## 一、查看 Mapping

### 1.1 查看完整 Mapping
```
GET kb_document_v1/_mapping
```

### 1.2 只查看某个字段的定义
```
GET kb_document_v1/_mapping/field/metadata.title
```

### 1.3 查看多个字段
```
GET kb_document_v1/_mapping/field/metadata.title,metadata.section_path,metadata.visibility
```

### 1.4 查看所有分区索引的 Mapping（通配符）
```
GET kb_document_*/_mapping
```

### 1.5 查看索引的 Settings（分片、副本数等）
```
GET kb_document_v1/_settings
```

---

## 二、热追加字段（PUT _mapping，无需 Reindex）

> **⚠️ 重要约束**：ES 只允许新增字段或扩展字段参数，**不允许修改已有字段的类型**。

### 2.1 新增 keyword 字段
```
PUT kb_document_v1/_mapping
{
  "properties": {
    "metadata": {
      "properties": {
        "new_keyword_field": {
          "type": "keyword"
        }
      }
    }
  }
}
```

### 2.2 新增 text 字段（带 IK 分词 + .keyword 子字段）
```
PUT kb_document_v1/_mapping
{
  "properties": {
    "metadata": {
      "properties": {
        "new_text_field": {
          "type": "text",
          "analyzer": "ik_max_word",
          "search_analyzer": "ik_smart",
          "fields": {
            "keyword": { "type": "keyword" }
          }
        }
      }
    }
  }
}
```

### 2.3 新增 date 字段
```
PUT kb_document_v1/_mapping
{
  "properties": {
    "metadata": {
      "properties": {
        "publish_time": {
          "type": "date",
          "format": "yyyy-MM-dd||epoch_millis"
        }
      }
    }
  }
}
```

### 2.4 新增顶层 float 字段（非嵌套在 metadata 内）
```
PUT kb_document_v1/_mapping
{
  "properties": {
    "quality_score": {
      "type": "float"
    }
  }
}
```

### 2.5 批量给所有分区索引同时追加字段（通配符）
```
PUT kb_document_*/_mapping
{
  "properties": {
    "metadata": {
      "properties": {
        "new_field": {
          "type": "keyword"
        }
      }
    }
  }
}
```

### 2.6 新增 object 字段（关闭动态 Mapping，防止字段爆炸）
```
PUT kb_document_v1/_mapping
{
  "properties": {
    "metadata": {
      "properties": {
        "extra_meta": {
          "type": "object",
          "dynamic": false
        }
      }
    }
  }
}
```

---

## 三、批量修改字段值（Update by Query）

> `_update_by_query` = 查询 + Painless 脚本原地修改，**不创建新文档**。
> `"conflicts": "proceed"` = 遇到版本冲突（正在写入的文档）跳过，而不是中断整个任务。

---

### 3.1 【最常用】给字段不存在的文档填充默认值

**场景**：新增字段后，给存量文档补充默认值。

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": {
    "bool": {
      "must_not": {
        "exists": { "field": "metadata.visibility" }
      }
    }
  },
  "script": {
    "source": "ctx._source.metadata.visibility = params.val",
    "lang": "painless",
    "params": { "val": "public" }
  }
}
```

---

### 3.2 精确匹配某个值后修改为新值

**场景**：把 `data_source = "old_system"` 的文档全部改成 `"new_system"`。

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": {
    "term": {
      "metadata.data_source": "old_system"
    }
  },
  "script": {
    "source": "ctx._source.metadata.data_source = params.val",
    "lang": "painless",
    "params": { "val": "new_system" }
  }
}
```

---

### 3.3 条件过滤 + 修改多个字段（一次 Script 改多个字段）

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": {
    "term": { "metadata.owner_dept_id": "dept_001" }
  },
  "script": {
    "source": """
      ctx._source.metadata.dept_l2 = params.l2;
      ctx._source.metadata.dept_l4 = params.l4;
      ctx._source.metadata.visibility = params.vis;
    """,
    "lang": "painless",
    "params": {
      "l2": "02",
      "l4": "0201",
      "vis": "internal"
    }
  }
}
```

---

### 3.4 全量更新（无过滤条件，更新所有文档）

> ⚠️ 高危操作，请确认索引名和字段名无误再执行。

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": { "match_all": {} },
  "script": {
    "source": "ctx._source.metadata.is_latest = params.val",
    "lang": "painless",
    "params": { "val": true }
  }
}
```

---

### 3.5 删除文档中的某个字段（从 _source 中移除 key）

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": { "match_all": {} },
  "script": {
    "source": "ctx._source.metadata.remove('old_unused_field')",
    "lang": "painless"
  }
}
```

---

### 3.6 数值型字段批量递增

**场景**：给所有文档的 `doc_version` 加 1。

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": { "match_all": {} },
  "script": {
    "source": """
      if (ctx._source.metadata.doc_version != null) {
        ctx._source.metadata.doc_version += 1;
      } else {
        ctx._source.metadata.doc_version = 1;
      }
    """,
    "lang": "painless"
  }
}
```

---

### 3.7 条件判断修改（Painless if/else）

**场景**：`quality_score` 为 null 或 0 的文档，设为默认分 0.5。

```
POST kb_document_v1/_update_by_query?conflicts=proceed
{
  "query": {
    "bool": {
      "should": [
        { "bool": { "must_not": { "exists": { "field": "metadata.quality_score" } } } },
        { "term": { "metadata.quality_score": 0 } }
      ],
      "minimum_should_match": 1
    }
  },
  "script": {
    "source": "ctx._source.metadata.quality_score = params.default_score",
    "lang": "painless",
    "params": { "default_score": 0.5 }
  }
}
```

---

### 3.8 跨索引批量更新（通配符，所有分区同时更新）

```
POST kb_document_*/_update_by_query?conflicts=proceed
{
  "query": {
    "bool": {
      "must_not": { "exists": { "field": "metadata.acl_tokens" } }
    }
  },
  "script": {
    "source": "ctx._source.metadata.acl_tokens = []",
    "lang": "painless"
  }
}
```

---

## 四、查看/监控任务进度

> `_update_by_query` 数据量大时会在后台异步执行，用以下命令监控。

### 4.1 查看所有后台任务
```
GET _tasks?actions=*update*&detailed=true
```

### 4.2 查看特定任务进度（用上面返回的 task_id）
```
GET _tasks/<task_id>
```

### 4.3 取消任务（误操作时紧急中止）
```
POST _tasks/<task_id>/_cancel
```

---

## 五、先预估影响范围（执行前必做）

> **最佳实践**：执行 `_update_by_query` 前，先用 `_count` 预估影响文档数。

### 5.1 预估满足条件的文档数
```
GET kb_document_v1/_count
{
  "query": {
    "bool": {
      "must_not": {
        "exists": { "field": "metadata.visibility" }
      }
    }
  }
}
```

### 5.2 查看样例文档（确认字段结构）
```
GET kb_document_v1/_search
{
  "size": 3,
  "query": {
    "bool": {
      "must_not": {
        "exists": { "field": "metadata.visibility" }
      }
    }
  },
  "_source": ["metadata.source", "metadata.visibility", "metadata.data_source"]
}
```

---

## 六、别名管理

### 6.1 查看索引的所有别名
```
GET kb_document_v1/_alias
```

### 6.2 查看所有 kb_document* 别名
```
GET _alias/kb_document*
```

### 6.3 给索引添加别名
```
POST _aliases
{
  "actions": [
    {
      "add": {
        "index": "kb_document_v1",
        "alias": "kb_document",
        "is_write_index": true
      }
    }
  ]
}
```

### 6.4 原子切换别名（零停机 Reindex 后切换）
```
POST _aliases
{
  "actions": [
    { "remove": { "index": "kb_document_v1", "alias": "kb_document" } },
    { "add":    { "index": "kb_document_v2", "alias": "kb_document", "is_write_index": true } }
  ]
}
```

---

## 七、Reindex（修改字段类型时使用）

> 当需要修改字段类型（如把 `keyword` 改成 `text`）时，只能走 Reindex 流程。

### 7.1 标准 Reindex（全量迁移）
```
POST _reindex
{
  "source": { "index": "kb_document_v1" },
  "dest":   { "index": "kb_document_v2" }
}
```

### 7.2 Reindex 并同时做字段转换（Script 版）
```
POST _reindex
{
  "source": { "index": "kb_document_v1" },
  "dest":   { "index": "kb_document_v2" },
  "script": {
    "source": """
      // 字段重命名：old_field -> new_field
      if (ctx._source.containsKey('old_field')) {
        ctx._source.new_field = ctx._source.old_field;
        ctx._source.remove('old_field');
      }
    """,
    "lang": "painless"
  }
}
```

### 7.3 异步执行大规模 Reindex（加 wait_for_completion=false）
```
POST _reindex?wait_for_completion=false
{
  "source": { "index": "kb_document_v1" },
  "dest":   { "index": "kb_document_v2" }
}
```

---

## 八、快速诊断命令

### 8.1 查看索引状态（文档数、大小、分片状态）
```
GET _cat/indices/kb_document*?v&h=index,status,docs.count,store.size,pri,rep
```

### 8.2 查看某索引的文档总数
```
GET kb_document_v1/_count
```

### 8.3 确认字段是否存在（查询有该字段的文档数）
```
GET kb_document_v1/_count
{
  "query": {
    "exists": { "field": "metadata.title" }
  }
}
```

### 8.4 查看 ES 集群健康状态
```
GET _cluster/health
```

### 8.5 查看 Index Template
```
GET _index_template/kb_document_template
```
