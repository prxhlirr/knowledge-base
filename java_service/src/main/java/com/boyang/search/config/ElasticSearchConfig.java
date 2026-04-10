package com.boyang.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch Java API Client 配置（A-5 修复：增加 Basic Auth 支持）。
 * 业务功能：创建带认证的 ES 客户端，与 Python 端 rag_pipeline.py 的 ES Auth 修复对齐。
 * [A-5 修复] 支持通过 elasticsearch.username/password 配置 Basic Auth，
 *              防止网络隔离失效时 ES 数据完全暴露。
 */
@Configuration
public class ElasticSearchConfig {

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    /** ES 认证用户名（空则不启用认证） */
    @Value("${elasticsearch.username:}")
    private String username;

    /** ES 认证密码（空则不启用认证） */
    @Value("${elasticsearch.password:}")
    private String password;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "http"));

        // [A-5 修复] 若配置了 ES 认证，启用 Basic Auth
        if (username != null && !username.isEmpty()) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(
                httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(cp));
        }

        RestClientTransport transport = new RestClientTransport(
            builder.build(), new JacksonJsonpMapper()
        );
        return new ElasticsearchClient(transport);
    }
}
