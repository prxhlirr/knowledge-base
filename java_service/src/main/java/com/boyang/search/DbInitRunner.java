package com.boyang.search;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// [重构] 已被 Flyway 迁移脚本（V1__init_schema.sql）替代，禁用此 Bean。
// 保留文件仅供历史参考，请勿删除 @Profile("disabled") 注解。
@Profile("disabled")
@Component
public class DbInitRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DbInitRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        try {
            // 建表
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.sys_tenant_policy (" +
                    "id bigserial PRIMARY KEY," +
                    "app_code varchar(64) NOT NULL UNIQUE," +
                    "allowed_indices varchar(255) NOT NULL," +
                    "force_file_type varchar(64)," +
                    "min_security_level int DEFAULT 0," +
                    "created_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "is_deleted smallint DEFAULT 0" +
                    ")");

            // 导入大批次表
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.sys_doc_batch (" +
                    "id bigserial PRIMARY KEY," +
                    "batch_id varchar(64) NOT NULL UNIQUE," +
                    "source_dir varchar(512)," +
                    "total_count int DEFAULT 0," +
                    "success_count int DEFAULT 0," +
                    "error_count int DEFAULT 0," +
                    "status varchar(32) DEFAULT 'PENDING'," + // PENDING, IMPORTING, DONE, FAILED
                    "created_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "is_deleted smallint DEFAULT 0" +
                    ")");
            // 扩展字段: 接入模式与原始信息
            try { jdbcTemplate.execute("ALTER TABLE public.sys_doc_batch ADD COLUMN IF NOT EXISTS ingest_mode varchar(32) DEFAULT 'LOCAL_DIR'"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("ALTER TABLE public.sys_doc_batch ADD COLUMN IF NOT EXISTS source_info text"); } catch (Exception ignore) {}

            // 导入原子任务表
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.sys_doc_import_task (" +
                    "id bigserial PRIMARY KEY," +
                    "task_id varchar(64) NOT NULL UNIQUE," +
                    "batch_id varchar(64) NOT NULL," +
                    "file_path varchar(1024) NOT NULL," +
                    "status varchar(32) DEFAULT 'IDLE'," + // IDLE, PENDING, PARSING, INDEXED, ERROR
                    "error_msg text," +
                    "created_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at timestamp DEFAULT CURRENT_TIMESTAMP," +
                    "is_deleted smallint DEFAULT 0" +
                    ")");
            // 扩展字段: 原始文件名
            try { jdbcTemplate.execute("ALTER TABLE public.sys_doc_import_task ADD COLUMN IF NOT EXISTS original_name varchar(255)"); } catch (Exception ignore) {}
            // [B-3 修复] 扩展字段：原始权限信息，RecoveryJob 重推时从此读取，防止权限静默降级
            try { jdbcTemplate.execute("ALTER TABLE public.sys_doc_import_task ADD COLUMN IF NOT EXISTS visibility varchar(32) DEFAULT 'INTERNAL'"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("ALTER TABLE public.sys_doc_import_task ADD COLUMN IF NOT EXISTS dept_code varchar(32)"); } catch (Exception ignore) {}

            // 迁移旧有的常量字典到数据库
            jdbcTemplate.execute("INSERT INTO public.sys_tenant_policy " +
                    "(app_code, allowed_indices, force_file_type, min_security_level) VALUES " +
                    "('ADMIN_MASTER_KEY', 'kb_*', NULL, 0)," +
                    "('VEND_A_7788', 'kb_document*', 'document', 0)," +
                    "('VEND_B_9900', 'kb_document*', NULL, 1) " +
                    "ON CONFLICT (app_code) DO NOTHING");

            System.out.println("========== [迁移完成] sys_tenant_policy 数据库动态字典已就位 ==========");

            // ===== [C5] 权限事件溯源表（启动时幂等建表）=====
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.doc_permission_events (" +
                "id          BIGSERIAL PRIMARY KEY," +
                "doc_id      VARCHAR(512) NOT NULL," +
                "action      VARCHAR(32)  NOT NULL CHECK (action IN ('GRANT','REVOKE','HANDLER','VISIBILITY_CHANGE'))," +
                "target_type VARCHAR(16)  NOT NULL CHECK (target_type IN ('USER','DEPT','GROUP'))," +
                "target_value VARCHAR(128) NOT NULL," +
                "operator_id VARCHAR(64)  NOT NULL," +
                "remark      VARCHAR(256)," +
                "created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                ")");
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dpe_doc ON public.doc_permission_events (doc_id)"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dpe_time ON public.doc_permission_events (created_at)"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dpe_target ON public.doc_permission_events (target_type, target_value)"); } catch (Exception ignore) {}

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.permission_groups (" +
                "id          BIGSERIAL PRIMARY KEY," +
                "group_code  VARCHAR(64)  UNIQUE NOT NULL," +
                "group_name  VARCHAR(128) NOT NULL," +
                "description VARCHAR(256)," +
                "created_by  VARCHAR(64)," +
                "created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
                ")");

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.permission_group_members (" +
                "id          BIGSERIAL PRIMARY KEY," +
                "group_code  VARCHAR(64)  NOT NULL," +
                "dept_code   VARCHAR(32)," +
                "user_id     VARCHAR(64)," +
                "granted_by  VARCHAR(64)," +
                "granted_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP" +
                ")");
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_pgm_group ON public.permission_group_members (group_code)"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_pgm_dept  ON public.permission_group_members (dept_code)");  } catch (Exception ignore) {}

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.doc_version_history (" +
                "id           BIGSERIAL PRIMARY KEY," +
                "source_name  VARCHAR(512) NOT NULL," +
                "doc_version  INT          NOT NULL," +
                "content_hash VARCHAR(64)," +
                "chunk_count  INT," +
                "operator_id  VARCHAR(64)," +
                "visibility   VARCHAR(32)  DEFAULT 'INTERNAL'," +
                "created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE (source_name, doc_version)" +
                ")");
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dvh_source ON public.doc_version_history (source_name)"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dvh_hash   ON public.doc_version_history (content_hash)"); } catch (Exception ignore) {}

            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.dept_tree (" +
                "dept_code   VARCHAR(32)  PRIMARY KEY," +
                "dept_name   VARCHAR(128) NOT NULL," +
                "dept_l2     VARCHAR(4)," +
                "dept_l4     VARCHAR(8)," +
                "dept_l6     VARCHAR(12)," +
                "dept_l9     VARCHAR(18)," +
                "dept_level  SMALLINT," +
                "parent_code VARCHAR(32)," +
                "is_active   SMALLINT     DEFAULT 1" +
                ")");

            System.out.println("========== [C5 完成] 权限事件溯源 5 张表已就位 ==========");

            // ===== [文档注册中心] kb_doc_registry（启动时幂等建表）=====
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.kb_doc_registry (" +
                "id            BIGSERIAL     PRIMARY KEY," +
                "doc_id        VARCHAR(256)  NOT NULL," +
                "source_name   VARCHAR(512)  NOT NULL," +
                "doc_version   INT           NOT NULL DEFAULT 1," +
                "is_latest     SMALLINT      NOT NULL DEFAULT 1," +
                "storage_path  VARCHAR(1024)," +
                "target_index  VARCHAR(128)  NOT NULL DEFAULT 'kb_document_v1'," +
                "chunk_count   INT           DEFAULT 0," +
                "content_hash  VARCHAR(64)," +
                "doc_number    VARCHAR(128)," +
                "unit          VARCHAR(256)," +
                "tags          VARCHAR(512)," +
                "publish_time  DATE," +
                "visibility    VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL'," +
                "dept_code     VARCHAR(32)," +
                "uploader_id   VARCHAR(64)," +
                "uploader_name VARCHAR(128)," +
                "status        VARCHAR(32)   NOT NULL DEFAULT 'INDEXED'," +
                "created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE (source_name, doc_version)" +
                ")");
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kdr_source   ON public.kb_doc_registry (source_name)");       } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kdr_latest   ON public.kb_doc_registry (is_latest, status)"); } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kdr_uploader ON public.kb_doc_registry (uploader_id)");       } catch (Exception ignore) {}
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kdr_dept     ON public.kb_doc_registry (dept_code)");         } catch (Exception ignore) {}

            System.out.println("========== [文档注册] kb_doc_registry 已就位 ==========");

            // ===== [P0-5 完整实现] GRANT 授权明细表 kb_doc_grants =====
            // 替代原来依赖 ES granted_users 字段的方案，MySQL 作为 GRANT 权限唯一权威来源。
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public.kb_doc_grants (" +
                "id            BIGSERIAL      PRIMARY KEY," +
                "registry_id   BIGINT         NOT NULL REFERENCES kb_doc_registry(id)," +
                "source_name   VARCHAR(512)   NOT NULL," +          // 冗余存储，避免 JOIN
                "grantee_id    VARCHAR(64)    NOT NULL," +           // 被授权用户 ID（对应 X-User-Id）
                "grantee_name  VARCHAR(128)," +                      // 被授权用户姓名（展示用）
                "expires_at    TIMESTAMP," +                         // NULL=永久授权
                "granted_by    VARCHAR(64)    NOT NULL," +           // 授权人 ID（审计）
                "remark        VARCHAR(256)," +                      // 授权原因
                "is_active     SMALLINT       NOT NULL DEFAULT 1," + // 1=有效，0=已撤销
                "created_at    TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "updated_at    TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                ")");
            // 核心唯一约束：同一文档同一用户只能有一条有效授权（防重复）
            try { jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_kdg_unique_grant " +
                  "ON public.kb_doc_grants (source_name, grantee_id) WHERE is_active = 1");
            } catch (Exception ignore) {}
            // 按 source_name 查询的索引（PermissionGuard.checkAccess 高频调用）
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kdg_source  ON public.kb_doc_grants (source_name, is_active)"); } catch (Exception ignore) {}
            // 按 grantee_id 查询的索引（未来支持"我有权限的文档"列表）
            try { jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kdg_grantee ON public.kb_doc_grants (grantee_id, is_active)");  } catch (Exception ignore) {}

            System.out.println("========== [P0-5 完成] kb_doc_grants 授权明细表已就位 ==========");

        } catch (Exception e) {
            System.err.println("========== [警告] 动态字典建表失败: " + e.getMessage());
        }
    }
}
