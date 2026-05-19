import psycopg2
try:
    conn = psycopg2.connect(dbname='knowledge_base', user='postgres', password='liyz', host='localhost')
    cur = conn.cursor()
    queries = [
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS es_query_timeout INT DEFAULT 1500;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS embedding_timeout INT DEFAULT 1500;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS rerank_timeout INT DEFAULT 1500;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS lexical_fast_path_max_length INT DEFAULT 4;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS adaptive_breaker_max_length INT DEFAULT 4;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS breaker_rrf_threshold NUMERIC(10,4) DEFAULT 0.0200;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS breaker_raw_score_threshold NUMERIC(10,4) DEFAULT 1.0000;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS truthful_ui_max_score_limit NUMERIC(10,4) DEFAULT 0.1500;',
        'ALTER TABLE sys_ai_tuning_config ADD COLUMN IF NOT EXISTS truthful_ui_ceiling NUMERIC(10,4) DEFAULT 0.7500;'
    ]
    for q in queries:
        cur.execute(q)
    conn.commit()
    cur.close()
    conn.close()
    print('DB Altered Successfully.')
except Exception as e:
    print('Error:', str(e))
