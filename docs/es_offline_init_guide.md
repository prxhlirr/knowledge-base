# Elasticsearch 离线初始化指南（索引与别名）

> 适用场景：内网/离线部署环境下，缺乏可视化工具，需通过命令行（Shell/Bash）直接对 Elasticsearch 进行索引的建立及别名绑定。
> 前提要求：目标服务器预装有 `curl`；已备好 `es_index_mapping.json` 文件。

---

## 准备工作

在向 ES 写任何数据前，需准备存放定义文档结构规则的 Mapping 文件。
在项目的部署目录中，确保拥有一个 `es_index_mapping.json` 的文件。其内部格式大概如下（简化示例）：

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "content": { "type": "text" },
      "metadata": {
        "properties": {
          "source": { "type": "keyword" }
        }
      }
    }
  }
}
```

---

## 离线执行脚本（一键完成）

以下提供了一份完整的 `init_es.sh` 脚本。建议实施人员将其与 `es_index_mapping.json` 置于同一级目录下直接执行。
该脚本能够处理在离线环境下常见的过程：**连接检查** -> **建立索引** -> **绑定别名**。

### 创建脚本文件

在服务器上新建脚本 `init_es.sh`：

```bash
touch init_es.sh
chmod +x init_es.sh
vi init_es.sh
```

将以下内容填入 `init_es.sh` 中：

```bash
#!/bin/bash
# ==========================================
# Elasticsearch 离线环境：索引及其别名初始化脚本
# ==========================================

# 【参数配置区】请根据现场环境修改
ES_HOST="http://localhost:9200"                     # ES 服务地址
INDEX_NAME="kb_document_v1"                         # 真实物理索引名称
ALIAS_NAME="kb_document"                            # 对外暴露的别名
MAPPING_FILE="./es_index_mapping.json"              # 当前目录下的 mapping 配置文件
# ES_AUTH="-u admin:password"                       # 若 ES 有账密，请取消注释并配置（注意 -u 后的空格）

echo "=========================================="
echo " 开始初始化 Elasticsearch，目标节点: $ES_HOST "
echo "=========================================="

# 1. 探活 ES 节点服务
echo ">>> [1/4] 测试 ES 连通性..."
HEALTH_CHECK=$(curl -s $ES_AUTH -o /dev/null -w "%{http_code}" "$ES_HOST/")
if [ "$HEALTH_CHECK" != "200" ]; then
  echo "❌ 错误: 无法连接到 Elasticsearch (状态码: $HEALTH_CHECK，请检查 ES 是否存活或账密是否正确。)"
  exit 1
fi
echo "✅ ES 节点访问正常"

# 2. 检查 Mapping 文件是否存在
echo ">>> [2/4] 检查文件 $MAPPING_FILE..."
if [ ! -f "$MAPPING_FILE" ]; then
    echo "❌ 错误: 找不到对应的 Mapping 文件: $MAPPING_FILE"
    exit 1
fi
echo "✅ 文件已准备就绪"

# 3. 创建索引（PUT 请求传输 json 内容）
echo ">>> [3/4] 开始创建索引: ${INDEX_NAME}..."
CREATE_RES=$(curl -s -X PUT "$ES_HOST/$INDEX_NAME" $ES_AUTH \
  -H "Content-Type: application/json" \
  -d @"$MAPPING_FILE")

# 简单判定返回包里面有没有 acknowledge 
if echo "$CREATE_RES" | grep -q '"acknowledged":true'; then
  echo "✅ 索引创建成功!"
else
  # 包含 "resource_already_exists_exception" 表示已存在
  if echo "$CREATE_RES" | grep -q 'resource_already_exists'; then
    echo "⚠️  索引 ${INDEX_NAME} 已经存在，尝试继续进行别名绑定。"
  else
    echo "❌ 索引创建可能发生了错误，响应结果为:"
    echo "$CREATE_RES"
    exit 1
  fi
fi

# 4. 创建别名（POST _aliases 操作）
echo ">>> [4/4] 绑定别名: ${ALIAS_NAME} -> ${INDEX_NAME}"
ALIAS_RES=$(curl -s -X POST "$ES_HOST/_aliases" $ES_AUTH \
  -H "Content-Type: application/json" \
  -d '{
    "actions": [
      {
        "add": {
          "index": "'"${INDEX_NAME}"'",
          "alias": "'"${ALIAS_NAME}"'",
          "is_write_index": true
        }
      }
    ]
  }')

if echo "$ALIAS_RES" | grep -q '"acknowledged":true'; then
  echo "✅ 别名绑定成功!"
else
  echo "❌ 别名绑定失败，响应结果为:"
  echo "$ALIAS_RES"
  exit 1
fi

echo "=========================================="
echo "🎉 初始化完成: 索引 $INDEX_NAME 与别名 $ALIAS_NAME 安装完毕!"
echo "=========================================="
```

---

## 验证与检查

在初始化操作结束后，实施人员可以通过在终端输入以下两条命令验证执行结果。

**1. 检查索引是否已经建立：**
```bash
curl -X GET "http://localhost:9200/_cat/indices/kb_document*?v"
```
*预期结果应能看到 `kb_document_v1` 的状态。*

**2. 检查别名指向是否正确：**
```bash
curl -X GET "http://localhost:9200/_alias/kb_document?pretty"
```
*预期结果应呈现 `kb_document_v1` 指向了该别名。*
