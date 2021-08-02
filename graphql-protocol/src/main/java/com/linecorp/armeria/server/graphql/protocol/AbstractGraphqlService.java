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

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.JacksonUtil;
import com.linecorp.armeria.server.AbstractHttpService;
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

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final QueryParams queryString = QueryParams.fromQueryString(ctx.query());
        String query = queryString.get("query");
        if (Strings.isNullOrEmpty(query)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Missing query");
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

        return executeGraphql(ctx, GraphqlRequest.of(query, operationName, variables, extensions,
                                                     produceType(req.headers())));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) throws Exception {
        final MediaType contentType = request.contentType();
        if (contentType == null) {
            return unsupportedMediaType();
        }

        if (contentType.isJson()) {
            return HttpResponse.from(request.aggregate().thenApply(req -> {
                final String body = req.contentUtf8();
                if (Strings.isNullOrEmpty(body)) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                           "Missing request body");
                }

                final Map<String, Object> requestMap;
                try {
                    requestMap = parseJsonString(body);
                } catch (JsonProcessingException ex) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                           "Failed to parse a JSON document: " + body);
                }

                final String query = (String) requestMap.get("query");
                if (Strings.isNullOrEmpty(query)) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Missing query");
                }
                final String operationName = (String) requestMap.get("operationName");
                final Map<String, Object> variables = toMap(requestMap.get("variables"));
                final Map<String, Object> extensions = toMap(requestMap.get("extensions"));

                try {
                    return executeGraphql(ctx, GraphqlRequest.of(query, operationName, variables, extensions,
                                                                 produceType(req.headers())));
                } catch (Exception ex) {
                    return HttpResponse.ofFailure(ex);
                }
            }));
        }

        if (contentType.is(MediaType.GRAPHQL)) {
            return HttpResponse.from(request.aggregate().thenApply(req -> {
                final String query = req.contentUtf8();
                if (Strings.isNullOrEmpty(query)) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Missing query");
                }

                try {
                    return executeGraphql(ctx, GraphqlRequest.of(query));
                } catch (Exception ex) {
                    return HttpResponse.ofFailure(ex);
                }
            }));
        }

        return unsupportedMediaType();
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
        return parseJsonString(value);
    }

    private static Map<String, Object> toMap(@Nullable Object maybeMap) {
        if (maybeMap == null) {
            return ImmutableMap.of();
        }

        if (maybeMap instanceof Map) {
            final Map<?, ?> variablesMap = (Map<?, ?>) maybeMap;
            if (variablesMap.isEmpty()) {
                return ImmutableMap.of();
            }

            final ImmutableMap.Builder<String, Object> builder =
                    ImmutableMap.builderWithExpectedSize(variablesMap.size());
            variablesMap.forEach((k, v) -> builder.put(String.valueOf(k), v));
            return builder.build();
        } else {
            throw new IllegalArgumentException("Unknown parameter type variables");
        }
    }

    private static Map<String, Object> parseJsonString(String content) throws JsonProcessingException {
        return mapper.readValue(content, JSON_MAP);
    }

    private static HttpResponse unsupportedMediaType() {
        return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                               MediaType.PLAIN_TEXT,
                               "Unsupported media type. Only JSON compatible types and " +
                               "application/graphql are supported.");
    }

    /**
     * Returns the negotiated {@link MediaType}. {@link MediaType#JSON} and {@link MediaType#GRAPHQL_JSON}
     * are commonly used for the Content-Type of a GraphQL response.
     * If {@link HttpHeaderNames#ACCEPT} is not specified, {@link MediaType#GRAPHQL_JSON} is used by default.
     *
     * <p>Note that the negotiated {@link MediaType} could not be used by the implementation of
     * {@link AbstractGraphqlService} which may choose to respond in one of several ways
     * specified the
     * <a href="https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#body">
     * specification</a>.
     */
    @Nullable
    private static MediaType produceType(RequestHeaders headers) {
        final List<MediaType>  acceptTypes = headers.accept();
        if (acceptTypes.isEmpty()) {
            // If there is no Accept header in the request, the response MUST include
            // a Content-Type: application/graphql+json header
            return MediaType.GRAPHQL_JSON;
        }

        for (MediaType accept : acceptTypes) {
            if (accept.is(MediaType.GRAPHQL_JSON) || accept.is(MediaType.JSON)) {
                return accept;
            }
        }

        // Not acceptable
        return null;
    }
}
