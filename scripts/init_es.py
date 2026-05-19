import sys
import os
sys.path.append(os.path.join(os.path.dirname(__file__), '..', 'ai_service'))
try:
    from core.vector_store import EsVectorStore
    print("Initiating ES connection...")
    store = EsVectorStore(host="http://localhost:9200")
    store.setup_schema()
    print("Index initialization logic executed!")
except Exception as e:
    print(f"Error occurred: {str(e)}")
