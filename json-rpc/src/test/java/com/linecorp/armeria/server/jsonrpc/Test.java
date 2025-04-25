package com.linecorp.armeria.server.jsonrpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.Assert.assertEquals;

public class Test {
    private static final ObjectMapper mapper = new ObjectMapper();


    private static class JsonRpcTestService {

        @Get("/method")
        @ProducesJson
        public Object returnResult() {
            return "asd";
        }

        @Get("/method2")
        @ProducesJson
        public Object returnResult2() {
            return "asd2";
        }
    }



    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("prefix:/test", new JsonRpcServiceBuilder(sb)
                    .addAnnotatedService("/a", new JsonRpcTestService())
                    .build()
            );
            sb.requestTimeoutMillis(0);

        }
    };

    private WebClient client() {
        return WebClient.builder(server.httpUri())
                        .responseTimeoutMillis(0)
                        .build();
    }


    @org.junit.jupiter.api.Test
    void testRoute() throws Exception {
        AggregatedHttpResponse response = client().execute(
                        HttpRequest.builder()
                                .post("/test/a")
                                .content(MediaType.PLAIN_TEXT, "method2")
                                .build())
                .aggregate().join();


        System.out.println("Status: " + response.status());
        System.out.println("Content: " + response.contentUtf8());
        // Test the /test route
        assertEquals("asd", response.contentUtf8());
    }
}
