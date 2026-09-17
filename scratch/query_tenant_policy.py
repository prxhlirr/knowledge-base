import psycopg2

def main():
    try:
        conn = psycopg2.connect(
            host="localhost",
            database="knowledge_base",
            user="postgres",
            password="liyz"
        )
        cur = conn.cursor()
        cur.execute("SELECT app_code, name, index_pattern, is_active FROM sys_tenant_policy;")
        rows = cur.fetchall()
        print("========== sys_tenant_policy 数据列表 ==========")
        for row in rows:
            print(f"app_code: {row[0]} | name: {row[1]} | index_pattern: {row[2]} | is_active: {row[3]}")
        cur.close()
        conn.close()
    except Exception as e:
        print("Error connecting to database:", e)

if __name__ == "__main__":
    main()
