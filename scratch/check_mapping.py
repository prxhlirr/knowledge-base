import json
from elasticsearch import Elasticsearch

def main():
    es = Elasticsearch("http://localhost:9200")
    
    mapping1 = {}
    mapping2 = {}
    
    try:
        mapping1 = es.indices.get_mapping(index="kb_doc_search_v1").body
    except Exception as e:
        print("Error getting kb_doc_search_v1 mapping:", e)
        
    try:
        mapping2 = es.indices.get_mapping(index="kb_document_official").body
    except Exception as e:
        print("Error getting kb_document_official mapping:", e)
        
    output = {
        "kb_doc_search_v1": mapping1,
        "kb_document_official": mapping2
    }
    
    with open("scratch/mappings.json", "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
        
    print("Done writing to scratch/mappings.json")

if __name__ == "__main__":
    main()
