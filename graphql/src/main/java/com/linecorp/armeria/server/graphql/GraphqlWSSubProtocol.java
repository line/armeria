/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.graphql;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.maybeTruncate;
import static java.util.Collections.emptyList;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dataloader.DataLoaderRegistry;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

/**
 * Handles the graphql-ws sub protocol within a web socket.
 * Handles potentially multiple subscriptions through the same web socket.
 */
class GraphqlWSSubProtocol {
    private static final Logger logger = LoggerFactory.getLogger(GraphqlWSSubProtocol.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HashMap<String, ExecutionResultSubscriber> graphqlSubscriptions = new HashMap<>();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private boolean connectionInitiated;

    private final ServiceRequestContext ctx;
    private final GraphqlExecutor graphqlExecutor;
    private final Function<? super ServiceRequestContext, ? extends DataLoaderRegistry>
            dataLoaderRegistryFunction;
    private final Map<String, Object> upgradeCtx;
    private Map<String, Object> connectionCtx = ImmutableMap.of();

    GraphqlWSSubProtocol(
            ServiceRequestContext ctx,
            GraphqlExecutor graphqlExecutor,
            Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFunction) {
        this.ctx = ctx;
        this.graphqlExecutor = graphqlExecutor;
        this.dataLoaderRegistryFunction = dataLoaderRegistryFunction;
        upgradeCtx = GraphqlServiceContexts.graphqlContext(ctx);
    }

    /**
     * Called when a binary frame is received. Binary frames are not supported by the graphql-ws protocol.
     */
    @Nullable
    public void handleBinary(WebSocketWriter out) {
        out.close(WebSocketCloseStatus.INVALID_MESSAGE_TYPE, "Binary frames are not supported");
    }

    /**
     * Receives an event and returns a response if one should be sent.
     */
    @Nullable
    public void handleText(String event, WebSocketWriter out) {
        if (!out.isOpen()) {
            return;
        }

        try {
            final Map<String, Object> eventMap = parseJsonString(event, JSON_MAP);
            final String type = toStringFromJson(eventMap.get("type"));
            if (type == null) {
                throw new GraphqlWebSocketCloseException(4400, "type is required");
            }
            final String id;

            switch (type) {
                case "connection_init":
                    if (connectionInitiated) {
                        // Already initiated, that's an error
                        throw new GraphqlWebSocketCloseException(4429, "Already initiated");
                    }
                    final Object rawPayload = eventMap.get("payload");
                    if (rawPayload != null) {
                        connectionCtx = toMapFromJson(rawPayload);
                    }
                    connectionInitiated = true;
                    writeConnectionAck(out);
                    break;
                case "ping":
                    writePong(out);
                    break;
                case "pong":
                    break;
                case "subscribe":
                    ensureInitiated();
                    id = toStringFromJson(eventMap.get("id"));
                    if (id == null) {
                        throw new GraphqlWebSocketCloseException(4400, "id is required");
                    }
                    final Map<String, Object> payload = toMapFromJson(eventMap.get("payload"));
                    try {
                        if (graphqlSubscriptions.containsKey(id)) {
                            // Subscription already exists
                            throw new GraphqlWebSocketCloseException(4409, "Already subscribed");
                        }
                        final String operationName = toStringFromJson(payload.get("operationName"));
                        final String query = toStringFromJson(payload.get("query"));
                        final Map<String, Object> variables = toMapFromJson(payload.get("variables"));
                        final Map<String, Object> extensions = toMapFromJson(payload.get("extensions"));

                        final ExecutionInput executionInput =
                                ExecutionInput.newExecutionInput()
                                              .graphQLContext(connectionCtx)
                                              .graphQLContext(upgradeCtx)
                                              .query(query)
                                              .variables(variables)
                                              .operationName(operationName)
                                              .extensions(extensions)
                                              .dataLoaderRegistry(dataLoaderRegistryFunction.apply(ctx))
                                              .build();

                        final CompletableFuture<ExecutionResult> future =
                                graphqlExecutor.executeGraphql(ctx, executionInput);

                        future.handleAsync((executionResult, throwable) -> {
                            handleExecutionResult(out, id, executionResult, throwable);
                            return null;
                        }, ctx.eventLoop());
                    } catch (GraphqlWebSocketCloseException e) {
                        logger.debug("Error handling subscription", e);
                        // Also cancel subscription if present before closing websocket
                        final ExecutionResultSubscriber s = graphqlSubscriptions.remove(id);
                        if (s != null) {
                            s.setCompleted();
                        }
                        out.close(e.getWebSocketCloseStatus());
                    } catch (Exception e) {
                        logger.debug("Error handling subscription", e);
                        // Unknown but possibly recoverable error
                        writeError(out, id, e);
                        return;
                    }
                    break;
                case "complete":
                    ensureInitiated();
                    // Read id and remove that subscription
                    id = toStringFromJson(eventMap.get("id"));
                    if (id == null) {
                        throw new GraphqlWebSocketCloseException(4400, "id is required");
                    }
                    final ExecutionResultSubscriber s = graphqlSubscriptions.remove(id);
                    if (s != null) {
                        s.setCompleted();
                    }
                    return;
                default:
                    final String reasonPhrase = maybeTruncate("Unknown event type: " + type);
                    assert reasonPhrase != null;
                    throw new GraphqlWebSocketCloseException(4400, reasonPhrase);
            }
        } catch (GraphqlWebSocketCloseException e) {
            logger.debug("Error while handling event", e);
            out.close(e.getWebSocketCloseStatus());
        } catch (Exception e) {
            logger.debug("Error while handling event", e);
            out.close(e);
        }
    }

    private void handleExecutionResult(WebSocketWriter out, String id,
                                       @Nullable ExecutionResult executionResult, @Nullable Throwable t) {
        if (t != null) {
            logger.debug("Error handling subscription", t);
            writeError(out, id, t);
            return;
        }

        if (executionResult == null) {
            logger.debug("ExecutionResult was null but no error was thrown");
            writeError(out, id, new IllegalArgumentException("ExecutionResult was null"));
            return;
        }

        if (!executionResult.getErrors().isEmpty()) {
            try {
                writeError(out, id, executionResult.getErrors());
            } catch (JsonProcessingException e) {
                logger.warn("Error serializing error event", e);
                out.close(e);
            }
            return;
        }

        if (!(executionResult.getData() instanceof Publisher)) {
            writeError(out, id, new Exception("Result of operation was not a subscription"));
            return;
        }

        final Publisher<ExecutionResult> publisher = executionResult.getData();
        final StreamMessage<ExecutionResult> streamMessage = StreamMessage.of(publisher);

        final ExecutionResultSubscriber executionResultSubscriber =
                new ExecutionResultSubscriber(id, new GraphqlSubProtocol() {
                    boolean completed;

                    @Override
                    public void sendResult(String operationId, ExecutionResult executionResult)
                            throws JsonProcessingException {
                        writeNext(out, operationId, executionResult);
                    }

                    @Override
                    public void sendGraphqlErrors(List<GraphQLError> errors) throws JsonProcessingException {
                        writeError(out, id, errors);
                    }

                    @Override
                    public void completeWithError(Throwable cause) {
                        if (completed) {
                            return;
                        }
                        completed = true;
                        writeError(out, id, cause);
                        graphqlSubscriptions.remove(id);
                    }

                    @Override
                    public void complete() {
                        if (completed) {
                            return;
                        }
                        completed = true;
                        writeComplete(out, id);
                        graphqlSubscriptions.remove(id);
                    }
                });

        graphqlSubscriptions.put(id, executionResultSubscriber);
        streamMessage.subscribe(executionResultSubscriber, ctx.eventLoop());
    }

    void cancel() {
        for (ExecutionResultSubscriber subscriber : graphqlSubscriptions.values()) {
            subscriber.setCompleted();
        }
        graphqlSubscriptions.clear();
    }

    private void ensureInitiated() throws Exception {
        if (!connectionInitiated) {
            // ConnectionAck not sent yet. Must be closed with 4401 Unauthorized.
            throw new GraphqlWebSocketCloseException(4401, "Unauthorized");
        }
    }

    private static String serializeToJson(Object object) throws JsonProcessingException {
        return mapper.writer().writeValueAsString(object);
    }

    @Nullable
    private static String toStringFromJson(@Nullable Object value) throws GraphqlWebSocketCloseException {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return (String) value;
        } else {
            throw new GraphqlWebSocketCloseException(4400, "Expected string value");
        }
    }

    /**
     * This only works reliably if maybeMap is from Json, as maps(objects) in Json
     * can only have string keys.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMapFromJson(@Nullable Object maybeMap)
            throws GraphqlWebSocketCloseException {
        if (maybeMap == null) {
            return ImmutableMap.of();
        }

        if (maybeMap instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) maybeMap;
            if (map.isEmpty()) {
                return ImmutableMap.of();
            }
            return Collections.unmodifiableMap((Map<String, Object>) maybeMap);
        } else {
            throw new GraphqlWebSocketCloseException(4400, "Expected map value");
        }
    }

    private static <T> T parseJsonString(String content, TypeReference<T> typeReference)
            throws GraphqlWebSocketCloseException {
        try {
            return mapper.readValue(content, typeReference);
        } catch (JsonProcessingException e) {
            throw new GraphqlWebSocketCloseException(4400, "Invalid JSON");
        }
    }

    private static void writePong(WebSocketWriter out) {
        out.tryWrite("{\"type\":\"pong\"}");
    }

    private static void writeConnectionAck(WebSocketWriter out) {
        out.tryWrite("{\"type\":\"connection_ack\"}");
    }

    private static void writeNext(WebSocketWriter out, String operationId, ExecutionResult executionResult)
            throws JsonProcessingException {
        final Map<String, Object> response = ImmutableMap.of(
                "id", operationId,
                "type", "next",
                "payload", executionResult.toSpecification());
        final String event = serializeToJson(response);
        logger.trace("NEXT: {}", event);
        out.tryWrite(event);
    }

    private static void writeError(WebSocketWriter out, String operationId, List<GraphQLError> errors)
            throws JsonProcessingException {
        final List<Map<String, Object>> errorSpecifications =
                errors.stream().map(GraphQLError::toSpecification).collect(Collectors.toList());
        final Map<String, Object> errorResponse = ImmutableMap.of(
                "type", "error",
                "id", operationId,
                "payload", errorSpecifications);
        final String event = serializeToJson(errorResponse);
        logger.trace("ERROR: {}", event);
        out.tryWrite(event);
    }

    private static void writeError(WebSocketWriter out, String operationId, Throwable t) {
        final Map<String, Object> errorResponse = ImmutableMap.of(
                "type", "error",
                "id", operationId,
                "payload", ImmutableList.of(
                        new GraphQLError() {
                            @Override
                            public String getMessage() {
                                return t.getMessage();
                            }

                            @Override
                            public List<SourceLocation> getLocations() {
                                return emptyList();
                            }

                            @Override
                            public ErrorClassification getErrorType() {
                                return ErrorType.DataFetchingException;
                            }
                        }.toSpecification()
                ));
        try {
            final String event = serializeToJson(errorResponse);
            logger.trace("ERROR: {}", event);
            out.tryWrite(event);
        } catch (JsonProcessingException e) {
            logger.warn("Error serializing error event", e);
            out.close(e);
        }
    }

    private static void writeComplete(WebSocketWriter out, String operationId) {
        out.tryWrite("{\"type\":\"complete\",\"id\":\"" + operationId + "\"}");
    }

    private static final class GraphqlWebSocketCloseException extends Exception {
        private static final long serialVersionUID = 1196626539261081709L;

        private final WebSocketCloseStatus webSocketCloseStatus;

        GraphqlWebSocketCloseException(int code, String reason) {
            webSocketCloseStatus = WebSocketCloseStatus.ofPrivateUse(code, reason);
        }

        WebSocketCloseStatus getWebSocketCloseStatus() {
            return webSocketCloseStatus;
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}

