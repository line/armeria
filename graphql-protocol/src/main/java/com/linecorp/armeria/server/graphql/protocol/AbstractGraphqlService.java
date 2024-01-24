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

package com.linecorp.armeria.server.graphql.protocol;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.graphql.protocol.GraphqlRequest;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.FileAggregatedMultipart;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A skeletal <a href="https://graphql.org/learn/serving-over-http/">GraphQL HTTP service</a> implementation.
 */
@UnstableApi
public abstract class AbstractGraphqlService extends AbstractHttpService {

    /**
     * Should not use {@link JacksonUtil#newDefaultObjectMapper()} that will convert a JSON object into
     * `scala.Map` if a `jackson-module-scala` module is registered.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private static final TypeReference<Map<String, List<String>>> MAP_PARAM =
            new TypeReference<Map<String, List<String>>>() {};

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final QueryParams queryString = QueryParams.fromQueryString(ctx.query());
        String query = queryString.get("query");
        if (Strings.isNullOrEmpty(query)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "query is missing");
        }
        query = query.trim();
        if (query.startsWith("mutation")) {
            // GET requests MUST NOT be used for executing mutation operations.
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED, MediaType.PLAIN_TEXT,
                                   "Mutation is not allowed");
        }

        final String operationName = queryString.get("operationName");
        final Map<String, Object> variables;
        final Map<String, Object> extensions;
        try {
            variables = toMap(queryString.get("variables"));
            extensions = toMap(queryString.get("extensions"));
        } catch (JsonProcessingException ex) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                   "Failed to parse a GraphQL query: " + ctx.query());
        }

        return executeGraphql(ctx, GraphqlRequest.of(query, operationName, variables, extensions));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) throws Exception {
        final MediaType contentType = request.contentType();
        if (contentType == null) {
            return unsupportedMediaType();
        }

        // See https://github.com/jaydenseric/graphql-multipart-request-spec/blob/master/readme.md
        if (contentType.is(MediaType.MULTIPART_FORM_DATA)) {
            return HttpResponse.of(FileAggregatedMultipart.aggregateMultipart(ctx, request)
                                                          .thenApply(multipart -> {
                try {
                    final ListMultimap<String, String> multipartParams = multipart.params();
                    final String operationsParam = getValueFromMultipartParam("operations", multipartParams);
                    final String mapParam = getValueFromMultipartParam("map", multipartParams);

                    final Map<String, Object> operations = parseJsonString(operationsParam, JSON_MAP);
                    final Map<String, List<String>> map = parseJsonString(mapParam, MAP_PARAM);
                    final String query = toStringFromJson("query", operations.get("query"));
                    if (Strings.isNullOrEmpty(query)) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                               "query is missing: " + operationsParam);
                    }
                    final Map<String, Object> variables = toMapFromJson(operations.get("variables"));
                    final Map<String, Object> copiedVariables = new HashMap<>(variables);
                    final Map<String, Object> extensions = toMapFromJson(operations.get("extensions"));
                    for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                        final List<MultipartFile> multipartFiles = multipart.files().get(entry.getKey());
                        bindMultipartVariable(copiedVariables, entry.getValue(), multipartFiles);
                    }

                    return executeGraphql(ctx, GraphqlRequest.of(query, null, copiedVariables, extensions));
                } catch (JsonProcessingException ex) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                           "Failed to parse a JSON document: " + ex.getMessage());
                } catch (IllegalArgumentException ex) {
                    return createResponse(ex);
                } catch (Exception ex) {
                    return HttpResponse.ofFailure(ex);
                }
            }));
        }

        if (contentType.isJson()) {
            return HttpResponse.of(request.aggregate(ctx.eventLoop()).thenApply(req -> {
                try (SafeCloseable ignored = ctx.push()) {
                    final String body = req.contentUtf8();
                    if (Strings.isNullOrEmpty(body)) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                               "Missing request body");
                    }

                    try {
                        final Map<String, Object> requestMap = parseJsonString(body, JSON_MAP);
                        final String query = toStringFromJson("query", requestMap.get("query"));
                        if (Strings.isNullOrEmpty(query)) {
                            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                                   "query is missing");
                        }

                        final String operationName =
                                toStringFromJson("operationName", requestMap.get("operationName"));
                        final Map<String, Object> variables = toMapFromJson(requestMap.get("variables"));
                        final Map<String, Object> extensions = toMapFromJson(requestMap.get("extensions"));

                        return executeGraphql(ctx, GraphqlRequest.of(query, operationName,
                                                                     variables, extensions));
                    } catch (JsonProcessingException ex) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                               "Failed to parse a JSON document: " + body);
                    } catch (IllegalArgumentException ex) {
                        return createResponse(ex);
                    } catch (Exception ex) {
                        return HttpResponse.ofFailure(ex);
                    }
                }
            }));
        }

        if (contentType.is(MediaType.GRAPHQL)) {
            return HttpResponse.of(request.aggregate(ctx.eventLoop()).thenApply(req -> {
                try (SafeCloseable ignored = ctx.push()) {
                    final String query = req.contentUtf8();
                    if (Strings.isNullOrEmpty(query)) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                               "query is missing");
                    }

                    try {
                        return executeGraphql(ctx, GraphqlRequest.of(query));
                    } catch (Exception ex) {
                        return HttpResponse.ofFailure(ex);
                    }
                }
            }));
        }

        return unsupportedMediaType();
    }

    private static void bindMultipartVariable(Map<String, Object> variables, List<String> operationsPaths,
                                              @Nullable List<MultipartFile> multipartFiles) {
        if (multipartFiles == null || multipartFiles.isEmpty()) {
            return;
        }
        final MultipartFile multipartFile = multipartFiles.get(0);
        for (String objectPath : operationsPaths) {
            MultipartVariableMapper.mapVariable(objectPath, variables, multipartFile);
        }
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        // Response stream will be supported via WebSocket.
        final MediaType contentType = routingContext.contentType();
        if (contentType != null && contentType.is(MediaType.MULTIPART_FORM_DATA)) {
            return ExchangeType.REQUEST_STREAMING;
        }
        return ExchangeType.UNARY;
    }

    /**
     * Handles a {@link GraphqlRequest}.
     */
    protected abstract HttpResponse executeGraphql(ServiceRequestContext ctx, GraphqlRequest req)
            throws Exception;

    private static Map<String, Object> toMap(@Nullable String value) throws JsonProcessingException {
        if (Strings.isNullOrEmpty(value)) {
            return ImmutableMap.of();
        }
        return parseJsonString(value, JSON_MAP);
    }

    private static String getValueFromMultipartParam(String name, ListMultimap<String, String> params) {
        final List<String> list = params.get(name);
        if (list.size() > 1) {
            throw new IllegalArgumentException("More than one '" + name + "' received.");
        }
        if (list.isEmpty() || Strings.isNullOrEmpty(list.get(0))) {
            throw new IllegalArgumentException('\'' + name + "' form field is missing");
        }
        return list.get(0);
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

    private static <T> T parseJsonString(String content, TypeReference<T> typeReference)
            throws JsonProcessingException {
        return mapper.readValue(content, typeReference);
    }

    private static HttpResponse unsupportedMediaType() {
        return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                               MediaType.PLAIN_TEXT,
                               "Unsupported media type. Only JSON compatible types and " +
                               "application/graphql are supported.");
    }

    private static HttpResponse createResponse(IllegalArgumentException ex) {
        final String message = ex.getMessage() == null ? HttpStatus.BAD_REQUEST.reasonPhrase()
                                                       : ex.getMessage();
        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, message);
    }
}
