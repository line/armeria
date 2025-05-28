/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.jsonrpc.JsonRpcUtil;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Post;

class JsonRpcBuilderTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testAddAnnotationService() {
        final ServerBuilder sb = Server.builder();

        final JsonRpcService jsonRpcService =
                JsonRpcService.builder(sb)
                        .addAnnotatedService("/myRpc", new Object() {
                            @Post("/myMethod")
                            public HttpResponse myMethod() {
                                return HttpResponse.of("Hello");
                            }
                        }).build();

        assertFalse(jsonRpcService.routes().isEmpty());
        assertTrue(jsonRpcService.routes().stream()
                .anyMatch(r -> r.patternString().equals("/myRpc")));

        sb.service(jsonRpcService);
        final Server server = sb.build();
        assertTrue(server.serviceConfigs().stream()
                .anyMatch(s -> s.route().patternString().equals("/myRpc/myMethod")));
    }

    @Test
    void testAddService() throws Exception {
        final ServerBuilder sb = Server.builder();

        final JsonRpcService jsonRpcService =
                JsonRpcService.builder(sb)
                        .addService("/myRpc",
                                (ctx, req) -> HttpResponse.of(simpleHttpService(ctx, req)))
                        .build();

        assertFalse(jsonRpcService.routes().isEmpty());
        assertTrue(jsonRpcService.routes().stream()
                .anyMatch(r -> r.patternString().equals("/myRpc")));

        final HttpRequest req =
                HttpRequest.of(
                        HttpMethod.POST,
                        "/myRpc",
                        MediaType.JSON_UTF_8,
                        JsonRpcUtil.createJsonRpcRequestJsonString(
                                "method",
                                new String[] { "a", "b" },
                                "test",
                                mapper));

        final AggregatedHttpResponse res = jsonRpcService.serve(ServiceRequestContext.of(req), req)
                .aggregate()
                .join();

        final JsonNode expected =
                mapper.createObjectNode()
                        .put("id", "test")
                        .put("method", "method")
                        .put("isNotification", false)
                        .put("body", mapper.writeValueAsString(mapper.createArrayNode()
                                .add("a")
                                .add("b")));

        assertEquals(HttpStatus.OK, res.status());
        final JsonNode actual = mapper.readTree(res.contentUtf8());
        assertEquals(expected, actual);
    }

    private static CompletableFuture<HttpResponse> simpleHttpService(
            ServiceRequestContext ctx, HttpRequest request) {
        return request.aggregate().thenApply(
                aggregatedHttpRequest -> {
                    try {
                        return HttpResponse.of(
                                mapper.writeValueAsString(
                                        mapper.createObjectNode()
                                                .put("id",
                                                        ctx.attr(JsonRpcAttributes.ID).toString())
                                                .put("method",
                                                        ctx.attr(JsonRpcAttributes.METHOD).toString())
                                                .put("isNotification",
                                                        (boolean) ctx.attr(JsonRpcAttributes.IS_NOTIFICATION))
                                                .put("body",
                                                        aggregatedHttpRequest.contentUtf8())));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
