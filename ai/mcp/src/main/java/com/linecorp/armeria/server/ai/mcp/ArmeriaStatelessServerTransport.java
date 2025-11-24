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
/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.linecorp.armeria.server.ai.mcp;

import static com.linecorp.armeria.server.ai.mcp.ArmeriaStreamableServerTransportProvider.canAcceptSse;
import static com.linecorp.armeria.server.ai.mcp.ArmeriaStreamableServerTransportProvider.writeContext;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link McpStatelessServerTransport} for Armeria server.
 */
@UnstableApi
public final class ArmeriaStatelessServerTransport implements McpStatelessServerTransport {

    // Forked from https://github.com/modelcontextprotocol/java-sdk/blob/80d0ad82a6b88a8ce8756dad3d4c90c4ae62ca69/mcp-spring/mcp-spring-webflux/src/main/java/io/modelcontextprotocol/server/transport/WebFluxStatelessServerTransport.java
    // with modifications to work with Armeria

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaStatelessServerTransport.class);

    /**
     * Returns a new {@link ArmeriaStatelessServerTransport} with default settings.
     */
    public static ArmeriaStatelessServerTransport of() {
        return builder().build();
    }

    /**
     * Returns a new builder for {@link ArmeriaStatelessServerTransport}.
     */
    public static ArmeriaStatelessServerTransportBuilder builder() {
        return new ArmeriaStatelessServerTransportBuilder();
    }

    private final HttpService httpService = new McpStatelessService();
    private final McpJsonMapper jsonMapper;
    private final McpTransportContextExtractor<ServiceRequestContext> contextExtractor;
    @Nullable
    private McpStatelessServerHandler mcpHandler;

    private volatile boolean isClosing;

    ArmeriaStatelessServerTransport(McpJsonMapper jsonMapper,
                                    McpTransportContextExtractor<ServiceRequestContext> contextExtractor) {

        this.jsonMapper = jsonMapper;
        this.contextExtractor = contextExtractor;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
        this.mcpHandler = mcpHandler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> isClosing = true);
    }

    /**
     * Returns the {@link HttpService} that defines the transport's HTTP endpoints.
     * This {@link HttpService} should be registered to Armeria {@link Server}.
     *
     * <p>The {@link HttpService} defines one endpoint with two methods:
     * <ul>
     *   <li>GET - Unsupported, returns 405 METHOD NOT ALLOWED</li>
     *   <li>POST - For handling client requests and notifications</li>
     * </ul>
     */
    public HttpService httpService() {
        return httpService;
    }

    private class McpStatelessService extends AbstractHttpService {

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            if (isClosing) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT,
                                       "Server is shutting down");
            }

            if (!canAcceptSse(req.headers())) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                       "Accept header must include both application/json " +
                                       "and text/event-stream");
            }

            return HttpResponse.of(req.aggregate().thenCompose(agg -> {
                try {
                    return handlePost(ctx, agg.contentUtf8());
                } catch (IllegalArgumentException | IOException e) {
                    logger.warn("Failed to deserialize message: {}", e.getMessage());
                    final HttpResponse response =
                            HttpResponse.ofJson(HttpStatus.BAD_REQUEST, JsonRpcResponse.ofFailure(
                                    JsonRpcError.PARSE_ERROR.withData("Invalid message format")));
                    return UnmodifiableFuture.completedFuture(response);
                }
            }));
        }

        private CompletableFuture<HttpResponse> handlePost(ServiceRequestContext ctx, String jsonText)
                throws IOException {
            final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, jsonText);
            final McpTransportContext mcpContext = contextExtractor.extract(ctx);

            assert mcpHandler != null;
            if (message instanceof McpSchema.JSONRPCRequest rpcRequest) {
                return mcpHandler.handleRequest(mcpContext, rpcRequest)
                                 .contextWrite(writeContext(mcpContext))
                                 .toFuture().thenApply(rpcResponse -> {
                            try {
                                final String json = jsonMapper.writeValueAsString(rpcResponse);
                                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
                            } catch (IOException e) {
                                logger.warn("Failed to serialize response: {}", e.getMessage());
                                return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR,
                                                           JsonRpcResponse.ofFailure(
                                                                   JsonRpcError.INTERNAL_ERROR.withData(
                                                                           "Failed to serialize response")));
                            }
                        });
            } else if (message instanceof McpSchema.JSONRPCNotification notification) {
                return mcpHandler.handleNotification(mcpContext, notification)
                                 .contextWrite(writeContext(mcpContext))
                                 .toFuture()
                                 .thenApply(unused -> HttpResponse.of(ResponseHeaders.of(HttpStatus.ACCEPTED)));
            } else {
                final HttpResponse response = HttpResponse.ofJson(
                        HttpStatus.BAD_REQUEST,
                        JsonRpcResponse.ofFailure(JsonRpcError.INVALID_REQUEST.withData(
                                "The server accepts either requests or notifications")));
                return UnmodifiableFuture.completedFuture(response);
            }
        }
    }
}
