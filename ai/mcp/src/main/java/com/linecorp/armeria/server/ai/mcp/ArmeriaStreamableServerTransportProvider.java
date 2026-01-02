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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.sse.ServerSentEventBuilder;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.streaming.ServerSentEvents;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession.McpStreamableServerSessionStream;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Implementation of an Armeria based {@link McpStreamableServerTransportProvider}.
 */
@UnstableApi
public final class ArmeriaStreamableServerTransportProvider implements McpStreamableServerTransportProvider {
    private static final TypeRef<InitializeRequest> INIT_REQ_TYPE_REF = new TypeRef<>() {};

    // Forked from https://github.com/modelcontextprotocol/java-sdk/blob/14ff4a385dc8b953886a56966b675a0794b72638/mcp-spring/mcp-spring-webflux/src/main/java/io/modelcontextprotocol/server/transport/WebFluxStreamableServerTransportProvider.java
    // to adapt to Armeria HttpService.

    private static final Logger logger = LoggerFactory.getLogger(
            ArmeriaStreamableServerTransportProvider.class);

    private static final String MESSAGE_EVENT_TYPE = "message";

    /**
     * Returns a new {@link ArmeriaStreamableServerTransportProvider} with default settings.
     */
    public static ArmeriaStreamableServerTransportProvider of() {
        return builder().build();
    }

    /**
     * Returns a new builder for {@link ArmeriaStreamableServerTransportProvider}.
     */
    public static ArmeriaStreamableServerTransportProviderBuilder builder() {
        return new ArmeriaStreamableServerTransportProviderBuilder();
    }

    private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private final HttpService httpService = new McpStreamableService();

    private final McpJsonMapper jsonMapper;
    private final McpTransportContextExtractor<ServiceRequestContext> contextExtractor;
    private final boolean disallowDelete;
    @Nullable
    private final KeepAliveScheduler keepAliveScheduler;

    @Nullable
    private McpStreamableServerSession.Factory sessionFactory;
    private volatile boolean isClosing;

    ArmeriaStreamableServerTransportProvider(
            McpJsonMapper jsonMapper, McpTransportContextExtractor<ServiceRequestContext> contextExtractor,
            boolean disallowDelete, @Nullable Duration keepAliveInterval) {
        requireNonNull(jsonMapper, "jsonMapper");
        requireNonNull(contextExtractor, "contextExtractor");

        this.jsonMapper = jsonMapper;
        this.contextExtractor = contextExtractor;
        this.disallowDelete = disallowDelete;
        if (keepAliveInterval != null) {
            keepAliveScheduler = KeepAliveScheduler
                    .builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(sessions.values()))
                    .initialDelay(keepAliveInterval)
                    .interval(keepAliveInterval)
                    .build();

            keepAliveScheduler.start();
        } else {
            keepAliveScheduler = null;
        }
    }

    @Override
    public List<String> protocolVersions() {
        return ImmutableList.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
                                ProtocolVersions.MCP_2025_06_18);
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

        return Flux.fromIterable(sessions.values()).flatMap(session -> {
            return session.sendNotification(method, params)
                          .doOnError(e -> logger.warn("Failed to send message to session {}: {}",
                                                      session.getId(), e.getMessage()))
                          .onErrorComplete();
        }).then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            isClosing = true;
            return Flux.fromIterable(sessions.values())
                       .doFirst(() -> logger.debug("Initiating graceful shutdown with {} active sessions",
                                                   sessions.size()))
                       .flatMap(McpStreamableServerSession::closeGracefully)
                       .then();
        }).then().doOnSuccess(v -> {
            sessions.clear();
            if (keepAliveScheduler != null) {
                keepAliveScheduler.shutdown();
            }
        });
    }

    /**
     * Returns the {@link HttpService} that defines the transport's HTTP endpoints.
     * This {@link HttpService} should be registered to Armeria {@link Server}.
     *
     * <p>The {@link HttpService} defines one endpoint with three methods:
     * <ul>
     *   <li>GET - For the client listening SSE stream</li>
     *   <li>POST - For receiving client messages</li>
     *   <li>DELETE - For removing sessions</li>
     * </ul>
     */
    public HttpService httpService() {
        return httpService;
    }

    private final class McpStreamableService extends AbstractHttpService {

        @Override
        public HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            if (isClosing) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT,
                                       "Server is shutting down");
            }

            final List<MediaType> acceptHeaders = req.headers().accept();
            if (!acceptHeaders.contains(MediaType.EVENT_STREAM)) {
                return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MediaType.PLAIN_TEXT,
                                       "Accept header must include text/event-stream");
            }

            final String sessionId = req.headers().get(HttpHeaders.MCP_SESSION_ID, "");
            if (sessionId.isEmpty()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                       "Missing required header: " + HttpHeaders.MCP_SESSION_ID);
            }
            // Use a blocking execute since MCP implementations may perform blocking operations.
            final CompletableFuture<HttpResponse> future =
                    CompletableFuture.supplyAsync(() -> handleGet(ctx, req, sessionId),
                                                  ctx.blockingTaskExecutor());
            return HttpResponse.of(future);
        }

        private HttpResponse handleGet(ServiceRequestContext ctx, HttpRequest req, String sessionId) {
            final McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                       "No session found for id: " + sessionId);
            }

            final McpTransportContext mcpContext = contextExtractor.extract(ctx);
            final String lastEventId = req.headers().get(HttpHeaders.LAST_EVENT_ID, "");
            if (!lastEventId.isEmpty()) {
                final Flux<ServerSentEvent> events =
                        session.replay(lastEventId)
                               .contextWrite(writeContext(mcpContext))
                               .map(msg -> ServerSentEvent.ofData(serialize(msg)));
                return ServerSentEvents.fromPublisher(events);
            }

            final Flux<ServerSentEvent> events = Flux.<ServerSentEvent>create(sink -> {
                final ArmeriaStreamableMcpSessionTransport sessionTransport =
                        new ArmeriaStreamableMcpSessionTransport(ctx, sink);
                final McpStreamableServerSessionStream listeningStream =
                        session.listeningStream(sessionTransport);
                sink.onDispose(listeningStream::close);
            }).contextWrite(writeContext(mcpContext));
            return ServerSentEvents.fromPublisher(events);
        }

        /**
         * Handles incoming JSON-RPC messages from clients.
         *
         * @param req The incoming server request containing the JSON-RPC message
         */
        @Override
        public HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            if (isClosing) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT,
                                       "Server is shutting down");
            }

            if (!canAcceptSse(req.headers())) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                       "Accept header must include both application/json " +
                                       "and text/event-stream");
            }

            // Use a blocking execute since MCP implementations may perform blocking operations.
            return HttpResponse.of(req.aggregate().thenComposeAsync(agg -> {
                try {
                    return handlePost(ctx, agg.contentUtf8());
                } catch (IllegalArgumentException | IOException e) {
                    logger.debug("Failed to deserialize message: {}", e.getMessage(), e);
                    final HttpResponse response =
                            HttpResponse.ofJson(HttpStatus.BAD_REQUEST, JsonRpcResponse.ofFailure(
                                    JsonRpcError.PARSE_ERROR.withData("Invalid message format")));
                    return UnmodifiableFuture.completedFuture(response);
                }
            }, ctx.blockingTaskExecutor()));
        }

        private CompletableFuture<HttpResponse> handlePost(ServiceRequestContext ctx, String jsonText)
                throws IOException {
            final McpTransportContext mcpContext = contextExtractor.extract(ctx);
            final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, jsonText);
            ctx.logBuilder().requestContent(message, message);
            if (message instanceof McpSchema.JSONRPCRequest rpcRequest &&
                rpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
                final McpSchema.InitializeRequest initializeRequest =
                        jsonMapper.convertValue(rpcRequest.params(), INIT_REQ_TYPE_REF);
                assert sessionFactory != null;
                final McpStreamableServerSession.McpStreamableServerSessionInit init =
                        sessionFactory.startSession(initializeRequest);
                sessions.put(init.session().getId(), init.session());
                return init.initResult().map(initializeResult -> {
                               return serialize(new JSONRPCResponse(McpSchema.JSONRPC_VERSION, rpcRequest.id(),
                                                                    initializeResult, null));
                           })
                           .contextWrite(writeContext(mcpContext))
                           .toFuture().thenApply(initResult -> {
                            return HttpResponse.builder()
                                               .status(HttpStatus.OK)
                                               .header(HttpHeaders.MCP_SESSION_ID,
                                                       init.session().getId())
                                               .content(MediaType.JSON, initResult)
                                               .build();
                        });
            }

            final String sessionId = ctx.request().headers().get(HttpHeaders.MCP_SESSION_ID, "");
            if (sessionId.isEmpty()) {
                final HttpResponse response =
                        HttpResponse.ofJson(HttpStatus.BAD_REQUEST, JsonRpcResponse.ofFailure(
                                JsonRpcError.INVALID_REQUEST.withData("Session ID missing")));
                return UnmodifiableFuture.completedFuture(response);
            }

            final McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                final HttpResponse response =
                        HttpResponse.ofJson(HttpStatus.NOT_FOUND, JsonRpcResponse.ofFailure(
                                JsonRpcError.INVALID_REQUEST.withData("Session not found: " + sessionId)));
                return UnmodifiableFuture.completedFuture(response);
            }

            if (message instanceof McpSchema.JSONRPCResponse rpcResponse) {
                return session.accept(rpcResponse)
                              .contextWrite(writeContext(mcpContext))
                              .toFuture()
                              .thenApply(unused -> HttpResponse.of(ResponseHeaders.of(HttpStatus.ACCEPTED)));
            } else if (message instanceof McpSchema.JSONRPCNotification notification) {
                return session.accept(notification)
                              .contextWrite(writeContext(mcpContext))
                              .toFuture()
                              .thenApply(unused -> HttpResponse.of(ResponseHeaders.of(HttpStatus.ACCEPTED)));
            } else if (message instanceof McpSchema.JSONRPCRequest rpcRequest) {
                final Flux<ServerSentEvent> events = Flux.<ServerSentEvent>create(sink -> {
                    final ArmeriaStreamableMcpSessionTransport transport =
                            new ArmeriaStreamableMcpSessionTransport(ctx, sink);
                    final Mono<Void> stream = session.responseStream(rpcRequest, transport);
                    final Disposable streamSubscription =
                            stream.onErrorComplete(err -> {
                                      sink.error(err);
                                      return true;
                                  }).contextWrite(sink.contextView())
                                  .subscribe();
                    sink.onCancel(streamSubscription);
                }).contextWrite(writeContext(mcpContext));
                final HttpResponse response = ServerSentEvents.fromPublisher(events);
                return UnmodifiableFuture.completedFuture(response);
            } else {
                final HttpResponse response =
                        HttpResponse.ofJson(HttpStatus.BAD_REQUEST, JsonRpcResponse.ofFailure(
                                JsonRpcError.INVALID_REQUEST.withData("Unknown message type")));
                return UnmodifiableFuture.completedFuture(response);
            }
        }

        @Override
        protected HttpResponse doDelete(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            if (isClosing) {
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT,
                                       "Server is shutting down");
            }

            final String sessionId = req.headers().get(HttpHeaders.MCP_SESSION_ID, "");
            if (sessionId.isEmpty()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                       "Missing required header: " + HttpHeaders.MCP_SESSION_ID);
            }

            if (disallowDelete) {
                return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED, MediaType.PLAIN_TEXT,
                                       "Deleting sessions is not allowed");
            }

            final McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT,
                                       "No session found for id: " + sessionId);
            }

            final McpTransportContext mcpContext = contextExtractor.extract(ctx);
            return HttpResponse.of(session.delete()
                                          .contextWrite(writeContext(mcpContext))
                                          .toFuture()
                                          .thenApply(unused -> {
                                              sessions.remove(sessionId);
                                              return HttpResponse.of(HttpStatus.OK);
                                          }));
        }
    }

    private class ArmeriaStreamableMcpSessionTransport implements McpStreamableServerTransport {

        private final ServiceRequestContext ctx;
        private final FluxSink<ServerSentEvent> sink;

        ArmeriaStreamableMcpSessionTransport(ServiceRequestContext ctx, FluxSink<ServerSentEvent> sink) {
            this.ctx = ctx;
            this.sink = sink;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, @Nullable String messageId) {
            if (message instanceof McpSchema.JSONRPCResponse) {
                ctx.logBuilder().responseContent(message, message);
            }
            return Mono.fromSupplier(() -> {
                final String jsonText = serialize(message);
                final ServerSentEventBuilder builder = ServerSentEvent.builder();
                if (messageId != null) {
                    builder.id(messageId);
                }
                final ServerSentEvent event =
                        builder.event(MESSAGE_EVENT_TYPE)
                               .data(jsonText)
                               .build();
                sink.next(event);
                return null;
            }).doOnError(e -> {
                sink.error(Exceptions.unwrap(e));
            }).then();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(sink::complete);
        }

        @Override
        public void close() {
            sink.complete();
        }
    }

    private String serialize(JSONRPCMessage message) {
        try {
            return jsonMapper.writeValueAsString(message);
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
    }

    static Function<Context, Context> writeContext(McpTransportContext transportContext) {
        return ctx -> ctx.put(McpTransportContext.KEY, transportContext);
    }

    static boolean canAcceptSse(RequestHeaders headers) {
        final List<MediaType> acceptTypes = headers.accept();
        if (acceptTypes.isEmpty()) {
            return false;
        }
        boolean jsonMatched = false;
        boolean sseMatched = false;
        for (MediaType acceptType : acceptTypes) {
            if (acceptType.isJson()) {
                jsonMatched = true;
                if (sseMatched) {
                    return true;
                }
            }
            if (acceptType.is(MediaType.EVENT_STREAM)) {
                sseMatched = true;
                if (jsonMatched) {
                    return true;
                }
            }
        }
        return false;
    }
}
