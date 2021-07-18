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

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.internal.server.JacksonUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A skeletal <a href="https://graphql.org/learn/serving-over-http/">GraphQL HTTP service</a> implementation.
 */
public abstract class AbstractGraphqlService extends AbstractHttpService {

    /**
     * Should not use {@link JacksonUtil#newDefaultObjectMapper()} that will convert a JSON object into
     * `scala.Map` if `jackson-module-scala` module is registered.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final QueryParams queryString = QueryParams.fromQueryString(ctx.query());
        final String query = queryString.get("query");
        if (Strings.isNullOrEmpty(query)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Missing query");
        }
        final String operationName = queryString.get("operationName");
        final Map<String, Object> variables = toVariableMap(queryString.get("variables"));

        return executeGraphql(ctx, GraphqlRequest.of(query, operationName, variables));
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

                final Map<String, Object> requestMap = parseJsonString(body);
                final String query = (String) requestMap.get("query");
                if (Strings.isNullOrEmpty(query)) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Missing query");
                }
                final Map<String, Object> variables = toVariableMap(requestMap.get("variables"));
                final String operationName = (String) requestMap.get("operationName");

                try {
                    return executeGraphql(ctx, GraphqlRequest.of(query, operationName, variables));
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

    private static Map<String, Object> toVariableMap(@Nullable String value) {
        if (Strings.isNullOrEmpty(value)) {
            return ImmutableMap.of();
        }
        return parseJsonString(value);
    }

    private static Map<String, Object> toVariableMap(@Nullable Object variables) {
        if (variables == null) {
            return ImmutableMap.of();
        }

        if (variables instanceof Map) {
            final Map<?, ?> variablesMap = (Map<?, ?>) variables;
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

    private static Map<String, Object> parseJsonString(String content) {
        try {
            return mapper.readValue(content, JSON_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse a JSON document: " + content, e);
        }
    }

    private static HttpResponse unsupportedMediaType() {
        return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                               MediaType.PLAIN_TEXT,
                               "Unsupported media type. Only JSON compatible types and " +
                               "application/graphql are supported.");
    }
}
