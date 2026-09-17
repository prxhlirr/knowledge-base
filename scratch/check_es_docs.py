import json
from elasticsearch import Elasticsearch

def main():
    try:
        es = Elasticsearch("http://localhost:9200")
        
        # 查找 _id 为 13 的分片数据
        res = es.get(index="kb_document_v1", id="13")
        print("Chunk 13 source:")
        print(json.dumps(res['_source'], indent=2, ensure_ascii=False))
        
    except Exception as e:
        print("Error fetching chunk 13:", e)

if __name__ == "__main__":
    main()
