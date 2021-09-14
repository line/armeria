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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A <a href="https://spec.graphql.org/June2018/">GraphQL</a> request.
 */
@UnstableApi
public interface GraphqlRequest {

    /**
     * Returns a newly-created {@link GraphqlRequest} with the specified {@code query}.
     */
    static GraphqlRequest of(String query) {
        return of(query, null, ImmutableMap.of(), ImmutableMap.of(), MediaType.GRAPHQL_JSON);
    }

    /**
     * Returns a newly-created {@link GraphqlRequest} with the specified {@code query}, {@code operationName}
     * and {@code variables}.
     */
    static GraphqlRequest of(String query, @Nullable String operationName,
                             Map<String, Object> variables, Map<String, Object> extensions,
                             @Nullable MediaType produceType) {
        requireNonNull(query, "query");
        checkArgument(!query.isEmpty(), "query is empty");
        requireNonNull(variables, "variables");
        requireNonNull(extensions, "extensions");

        return new DefaultGraphqlRequest(query, operationName, variables, extensions, produceType);
    }

    /**
     * Returns the GraphQL query of the current request.
     */
    String query();

    /**
     * Returns the
     * <a href="https://spec.graphql.org/June2018/#sec-Named-Operation-Definitions">operation name</a>
     * of the {@link #query()}. If not specified, {@code null} is returned.
     */
    @Nullable
    String operationName();

    /**
     * Returns the <a href="https://spec.graphql.org/June2018/#sec-Language.Variables">variables</a>
     * of the {@link #query()}. If not specified, an empty {@link Map} is returned.
     */
    Map<String, Object> variables();

    /**
     * Returns the
     * <a href="https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#request-parameters">extensions</a>
     * of the {@link #query()}. This entry is reserved for implementors to extend the protocol.
     * If not specified, an empty {@link Map} is returned.
     */
    Map<String, Object> extensions();

    /**
     * Returns the {@link MediaType} obtained through content negotiation.
     * {@code null} if {@link HttpHeaderNames#ACCEPT} does not contain a {@link MediaType#GRAPHQL_JSON}
     * or a {@link MediaType#JSON}. The returned {@link MediaType} can be used as the default content type
     * for a GraphQL {@link HttpResponse}.
     *
     * <p>See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.2">Accept header</a> and
     * <a href="https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#body">GraphQL over HTTP</a>
     * for more information.
     */
    @Nullable
    MediaType produceType();
}
