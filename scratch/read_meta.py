import json

def main():
    with open("scratch/apple_docs_utf8.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        
    print("=== kb_document (Physical chunks) ===")
    for doc in data["kb_document"]:
        meta = doc["source"].get("metadata", {})
        print(f"ID: {doc['id']}")
        print(f"  metadata.doc_id: {repr(meta.get('doc_id'))}")
        print(f"  metadata.source: {repr(meta.get('source'))}")
        print(f"  metadata.chunk_id: {repr(meta.get('chunk_id'))}")
        print(f"  chunk_granularity: {doc['source'].get('chunk_granularity')}")
        print("-" * 40)
        
    print("\n=== kb_doc_search (Document index) ===")
    for doc in data["kb_doc_search"]:
        src = doc["source"]
        print(f"ID: {doc['id']}")
        print(f"  doc_id: {repr(src.get('doc_id'))}")
        print(f"  source: {repr(src.get('source'))}")
        print(f"  representative_chunk_ids: {src.get('representative_chunk_ids')}")
        print("-" * 40)

if __name__ == "__main__":
    main()
