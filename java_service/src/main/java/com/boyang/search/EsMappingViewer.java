package com.boyang.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

public class EsMappingViewer {
    public static void main(String[] args) {
        try {
            RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200, "http")
            ).build();
            RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient client = new ElasticsearchClient(transport);

            GetMappingResponse response = client.indices().getMapping(m -> m.index("knowledge_base"));
            System.out.println("ES Mapping for 'knowledge_base':");
            System.out.println(response.toString());
            
            restClient.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
