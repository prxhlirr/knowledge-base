import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DBUpdate {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/knowledge_base", "postgres", "liyz");
        Statement stmt = conn.createStatement();
        String[] queries = {
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN es_query_timeout INT DEFAULT 1500",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN embedding_timeout INT DEFAULT 1500",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN rerank_timeout INT DEFAULT 1500",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN lexical_fast_path_max_length INT DEFAULT 4",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN adaptive_breaker_max_length INT DEFAULT 4",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN breaker_rrf_threshold NUMERIC(10,4) DEFAULT 0.0200",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN breaker_raw_score_threshold NUMERIC(10,4) DEFAULT 1.0000",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN truthful_ui_max_score_limit NUMERIC(10,4) DEFAULT 0.1500",
            "ALTER TABLE sys_ai_tuning_config ADD COLUMN truthful_ui_ceiling NUMERIC(10,4) DEFAULT 0.7500"
        };
        for (String q : queries) {
            try { 
                stmt.execute(q); 
                System.out.println("Executed: " + q);
            } catch (Exception e) { 
                System.out.println("Skipped or Error: " + e.getMessage()); 
            }
        }
        System.out.println("DB Updated Successfully.");
    }
}
