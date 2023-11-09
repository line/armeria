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

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ErrorClassification;
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
    private final GraphqlExecutor graphqlExecutor;
    private final HashMap<String, GraphqlSubscriber> graphqlSubscriptions = new HashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private boolean connectionInitiated;

    private final Map<String, Object> upgradeCtx;
    private Map<String, Object> connectionCtx = ImmutableMap.of();

    GraphqlWSSubProtocol(ServiceRequestContext ctx, GraphqlExecutor executor) {
        upgradeCtx = GraphqlServiceContexts.graphqlContext(ctx);
        graphqlExecutor = executor;
    }

    /**
     * Receives an event and returns a response if one should be sent.
     */
    @Nullable
    public void handle(String event, WebSocketWriter out) {
        try {
            final Map<String, Object> eventMap = parseJsonString(event, JSON_MAP);
            final String type = toStringFromJson("type", eventMap.get("type"));
            requireNonNull(type, "type");
            final String id;

            switch (type) {
                case "connection_init":
                    final Object rawPayload = eventMap.get("payload");
                    if (rawPayload != null) {
                        connectionCtx = toMapFromJson(rawPayload);
                    }
                    connectionInitiated = true;
                    writeConnectionAck(out);
                    break;
                case "ping":
                    ensureInitiated();
                    writePong(out);
                    break;
                case "pong":
                    ensureInitiated();
                    break;
                case "subscribe":
                    ensureInitiated();
                    id = toStringFromJson("id", eventMap.get("id"));
                    requireNonNull(id, "id");
                    final Map<String, Object> payload = toMapFromJson(eventMap.get("payload"));
                    try {
                        if (graphqlSubscriptions.containsKey(id)) {
                            throw new IllegalArgumentException(
                                    "Subscription with id " + id + " already exists");
                        }
                        final String operationName = toStringFromJson("operationName",
                                                                      payload.get("operationName"));
                        final String query = toStringFromJson("query", payload.get("query"));
                        final Map<String, Object> variables = toMapFromJson(payload.get("variables"));
                        final Map<String, Object> extensions = toMapFromJson(payload.get("extensions"));

                        final ExecutionInput.Builder executionInput = ExecutionInput.newExecutionInput()
                                                                                    .graphQLContext(upgradeCtx)
                                                                                    .graphQLContext(
                                                                                            connectionCtx)
                                                                                    .query(query)
                                                                                    .variables(variables)
                                                                                    .operationName(
                                                                                            operationName)
                                                                                    .extensions(extensions);

                        final ExecutionResult executionResult = graphqlExecutor.executeGraphql(executionInput);

                        if (!executionResult.getErrors().isEmpty()) {
                            writeError(out, id, executionResult.getErrors());
                            return;
                        }

                        final Publisher<ExecutionResult> publisher = executionResult.getData();

                        final GraphqlSubscriber executionResultSubscriber =
                                new GraphqlSubscriber(id, new GraphqlSubProtocol() {
                                    @Override
                                    public void sendResult(String operationId, ExecutionResult executionResult)
                                            throws JsonProcessingException {
                                        writeNext(out, operationId, executionResult);
                                    }

                                    @Override
                                    public void sendGraphqlErrors(List<GraphQLError> errors)
                                            throws JsonProcessingException {
                                        writeError(out, id, errors);
                                    }
                                });

                        graphqlSubscriptions.put(id, executionResultSubscriber);
                        publisher.subscribe(executionResultSubscriber);
                    } catch (Exception e) {
                        logger.debug("Error handling subscription", e);
                        GraphqlWSSubProtocol.this.writeError(out, id, e);
                        return;
                    }
                    break;
                case "complete":
                    ensureInitiated();
                    // Read id and remove that subscription
                    id = toStringFromJson("id", eventMap.get("id"));
                    requireNonNull(id, "id");
                    final GraphqlSubscriber s = graphqlSubscriptions.remove(id);
                    if (s != null) {
                        s.setCompleted();
                    }
                    return;
                default:
                    throw new IllegalArgumentException("Unknown event type: " + type);
            }
        } catch (Exception e) {
            logger.debug("Error while handling event", e);
            out.close(e);
        }
    }

    private void ensureInitiated() throws Exception {
        if (!connectionInitiated) {
            throw new Exception("Connection not initiated");
        }
    }

    private String serializeToJson(Object object) throws JsonProcessingException {
        return mapper.writer().writeValueAsString(object);
    }

    @Nullable
    private static String toStringFromJson(String name, @Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return (String) value;
        } else {
            throw new IllegalArgumentException("Invalid " + name + " (expected: string)");
        }
    }

    /**
     * This only works reliably if maybeMap is from Json, as maps(objects) in Json
     * can only have string keys.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMapFromJson(@Nullable Object maybeMap) {
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
            throw new IllegalArgumentException("Unknown parameter type variables");
        }
    }

    private <T> T parseJsonString(String content, TypeReference<T> typeReference)
            throws JsonProcessingException {
        return mapper.readValue(content, typeReference);
    }

    private void writePong(WebSocketWriter out) {
        out.write("{\"type\":\"pong\"}");
    }

    private void writeConnectionAck(WebSocketWriter out) {
        out.write("{\"type\":\"connection_ack\"}");
    }

    private void writeNext(WebSocketWriter out, String operationId, ExecutionResult executionResult)
            throws JsonProcessingException {
        final HashMap<String, Object> response = new HashMap<>();
        response.put("id", operationId);
        response.put("type", "next");
        response.put("payload", executionResult.toSpecification());
        final String event = serializeToJson(response);
        logger.trace("NEXT: {}", event);
        out.write(event);
    }

    private void writeError(WebSocketWriter out, String operationId, List<GraphQLError> errors)
            throws JsonProcessingException {
        final HashMap<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("id", operationId);
        errorResponse.put("payload", errors);
        final String event = serializeToJson(errorResponse);
        logger.trace("ERROR: {}", event);
        out.write(event);
    }

    private void writeError(WebSocketWriter out, String operationId, Throwable t)
            throws JsonProcessingException {
        final HashMap<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("id", operationId);
        errorResponse.put("payload", ImmutableList.of(
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
                        return ErrorClassification.errorClassification("Unknown");
                    }
                }
        ));
        final String event = serializeToJson(errorResponse);
        logger.trace("ERROR: {}", event);
        out.write(event);
    }
}

