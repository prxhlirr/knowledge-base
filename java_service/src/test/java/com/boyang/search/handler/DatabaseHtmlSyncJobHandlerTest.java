package com.boyang.search.handler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class DatabaseHtmlSyncJobHandlerTest {

    @Resource
    private DatabaseHtmlSyncJobHandler databaseHtmlSyncJobHandler;

    @Test
    public void testExecute() throws Exception {
        System.out.println("====== [TEST] 开始执行 dbHtmlExtractJob 联调测试 ======");
        databaseHtmlSyncJobHandler.execute("");
        System.out.println("====== [TEST] 执行完成 ======");
    }
}
