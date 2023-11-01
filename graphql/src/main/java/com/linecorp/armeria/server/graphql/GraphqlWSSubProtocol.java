package com.linecorp.armeria.server.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import graphql.ErrorClassification;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.linecorp.armeria.server.graphql.GraphqlServiceContexts.GRAPHQL_CONTEXT_KEY;
import static java.util.Collections.emptyList;

class GraphqlWSSubProtocol {
    private static final Logger logger = LoggerFactory.getLogger(GraphqlWSSubProtocol.class);
    private final GraphqlExecutor graphqlExecutor;
    private HashMap<String, GraphqlSubscriber> graphqlSubscriptions = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private final TypeReference<Map<String, Object>> JSON_MAP =
        new TypeReference<Map<String, Object>>() {
        };

    private boolean connectionInitiated = false;

    private Map<String, Object> ctx = ImmutableMap.of();

    GraphqlWSSubProtocol(GraphqlExecutor executor) {
        this.graphqlExecutor = executor;
    }

    /**
     * Receives an event and returns a response if one should be sent.
     */
    @Nullable
    public void handle(String event, WebSocketWriter out) {
        logger.debug("handle: {}", event);
        try {
            Map<String, Object> eventMap = parseJsonString(event, JSON_MAP);

            String type = toStringFromJson("type", eventMap.get("type"));
            String id;

            switch (type) {
                case "connection_init":
                    Object rawPayload = eventMap.get("payload");
                    if (rawPayload != null) {
                        ctx = toMapFromJson(rawPayload);
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
                    Map<String, Object> payload = toMapFromJson(eventMap.get("payload"));
                    try {
                        if (graphqlSubscriptions.containsKey(id)) {
                            throw new IllegalArgumentException("Subscription with id " + id + " already exists");
                        }
                        String operationName = toStringFromJson("operationName", payload.get("operationName"));
                        String query = toStringFromJson("query", payload.get("query"));
                        Map<String, Object> variables = toMapFromJson(payload.get("variables"));
                        Map<String, Object> extensions = toMapFromJson(payload.get("extensions"));

                        ExecutionInput.Builder executionInput = ExecutionInput.newExecutionInput()
                            .query(query)
                            .variables(variables)
                            .operationName(operationName)
                            .extensions(extensions)
                            .graphQLContext(ctx);

                        ExecutionResult executionResult = graphqlExecutor.executeGraphql(executionInput);

                        if (!executionResult.getErrors().isEmpty()) {
                            writeError(out, id, executionResult.getErrors());
                            return;
                        }

                        Publisher<ExecutionResult> publisher = executionResult.getData();

                        GraphqlSubscriber executionResultSubscriber = new GraphqlSubscriber(id, new GraphqlSubProtocol() {
                            @Override
                            public void sendResult(String operationId, ExecutionResult executionResult) throws JsonProcessingException {
                                logger.debug("Sending result: {}", executionResult);
                                writeNext(out, operationId, executionResult);
                            }

                            @Override
                            public void sendGraphqlErrors(List<GraphQLError> errors) throws JsonProcessingException {
                                writeError(out, id, errors);
                            }

                            @Override
                            public void sendError(Throwable t) throws JsonProcessingException {
                                logger.error("Error in subscription", t);
                                writeError(out, id, t);
                            }
                        });

                        graphqlSubscriptions.put(id, executionResultSubscriber);

                        publisher.subscribe(executionResultSubscriber);

                    } catch (Exception e) {
                        logger.error("Error handling subscription", e);
                        GraphqlWSSubProtocol.this.writeError(out, id, e);
                        return;
                    }
                    break;
                case "complete":
                    ensureInitiated();
                    // Read id and remove that subscription
                    id = toStringFromJson("id", eventMap.get("id"));
                    GraphqlSubscriber s = graphqlSubscriptions.remove(id);
                    if (s != null) {
                        s.setCompleted();
                    }
                    return;
                default:
                        /*
                        Receiving a message of a type or format which is not specified in this document will result in an immediate socket closure with the event 4400: <error-message>. The <error-message> can be vaguely descriptive on why the received message is invalid.
                         */
                    // TODO
                    throw new IllegalArgumentException("Unknown event type: " + type);
            }


        } catch (JsonProcessingException e) {
            logger.error("Error parsing json", e);
            // TODO
            throw new RuntimeException(e);
        } catch (IllegalStateException e) {
            logger.error("Error handling event", e);
            // TODO send error back
            throw new RuntimeException(e);
        }
    }

    private void ensureInitiated() throws IllegalStateException {
        if (!connectionInitiated) {
            throw new IllegalStateException("Connection not initiated");
        }
    }

    private String serializeToJson(Object object) throws JsonProcessingException {
        return mapper.writer().writeValueAsString(object);
    }

    @Nullable
    private String toStringFromJson(String name, @Nullable Object value) {
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
    private Map<String, Object> toMapFromJson(@Nullable Object maybeMap) {
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

    void writePong(WebSocketWriter out) {
        out.write("{\"type\":\"pong\"}");
    }

    void writeConnectionAck(WebSocketWriter out) {
        out.write("{\"type\":\"connection_ack\"}");
    }

    void writeNext(WebSocketWriter out, String operationId, ExecutionResult executionResult) throws JsonProcessingException {
        HashMap<String, Object> response = new HashMap<>();
        response.put("id", operationId);
        response.put("type", "next");
        response.put("payload", executionResult.toSpecification());
        String event = serializeToJson(response);
        logger.info("Writing next: {}", event);
        out.write(event);
    }

    void writeError(WebSocketWriter out, String operationId, List<GraphQLError> errors) throws JsonProcessingException {
        HashMap<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("id", operationId);
        errorResponse.put("payload", errors);
        String event = serializeToJson(errorResponse);
        logger.info("Writing error: {}", event);
        out.write(event);
    }

    void writeError(WebSocketWriter out, String operationId, Throwable t) throws JsonProcessingException {
        HashMap<String, Object> errorResponse = new HashMap<>();
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
                    // TODO ?
                    return ErrorClassification.errorClassification("Unknown");
                }
            }
        ));
        String event = serializeToJson(errorResponse);
        logger.info("Writing error: {}", event);
        out.write(event);
    }
}

