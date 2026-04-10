import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class InitSql {
    public static void main(String[] args) {
        try {
            Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/knowledge_base", "postgres", "liyz");
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS public.sys_tenant_policy (" +
                    "id bigserial PRIMARY KEY," +
                    "app_code varchar(64) NOT NULL UNIQUE," +
                    "allowed_indices varchar(255) NOT NULL," +
                    "force_file_type varchar(64)," +
                    "created_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "is_deleted smallint DEFAULT 0" +
                    ");");
            stmt.execute("INSERT INTO public.sys_tenant_policy (app_code, allowed_indices, force_file_type) VALUES " +
                    "('ADMIN_MASTER_KEY', 'kb_*', NULL)," +
                    "('VEND_A_7788', 'kb_document*', 'document')," +
                    "('VEND_B_9900', 'kb_document*', NULL) ON CONFLICT (app_code) DO NOTHING;");
            System.out.println("SQL Executed Successfully!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
