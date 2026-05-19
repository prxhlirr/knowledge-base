import redis
import os
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), "ai_service", ".env"))

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", 6379))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")

def check():
    try:
        r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASSWORD)
        length = r.llen("DOC_TASK_QUEUE")
        print(f"QUEUE_LENGTH: {length}")
    except Exception as e:
        print(f"ERROR: {e}")

if __name__ == "__main__":
    check()
