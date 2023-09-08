/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for {@link HttpJsonTranscodingOptions}.
 */
@UnstableApi
public final class HttpJsonTranscodingOptionsBuilder {

    private static final EnumSet<HttpJsonTranscodingQueryParamMatchRule> DEFAULT_QUERY_PARAM_MATCH_RULES =
            EnumSet.of(HttpJsonTranscodingQueryParamMatchRule.ORIGINAL_FIELD);

    private UnframedGrpcErrorHandler errorHandler = UnframedGrpcErrorHandler.ofJson();

    @Nullable
    private Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules;

    HttpJsonTranscodingOptionsBuilder() {}

    /**
     * Adds the specified {@link HttpJsonTranscodingQueryParamMatchRule} which is used
     * to match {@link QueryParams} of an {@link HttpRequest} with fields in a {@link Message}.
     * If not set, {@link HttpJsonTranscodingQueryParamMatchRule#ORIGINAL_FIELD} is used by default.
     */
    public HttpJsonTranscodingOptionsBuilder queryParamMatchRules(
            HttpJsonTranscodingQueryParamMatchRule... queryParamMatchRules) {
        requireNonNull(queryParamMatchRules, "queryParamMatchRules");
        queryParamMatchRules(ImmutableList.copyOf(queryParamMatchRules));
        return this;
    }

    /**
     * Adds the specified {@link HttpJsonTranscodingQueryParamMatchRule} which is used
     * to match {@link QueryParams} of an {@link HttpRequest} with fields in a {@link Message}.
     * If not set, {@link HttpJsonTranscodingQueryParamMatchRule#ORIGINAL_FIELD} is used by default.
     */
    public HttpJsonTranscodingOptionsBuilder queryParamMatchRules(
            Iterable<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules) {
        requireNonNull(queryParamMatchRules, "queryParamMatchRules");
        checkArgument(!Iterables.isEmpty(queryParamMatchRules), "Can't set an empty queryParamMatchRules");
        if (this.queryParamMatchRules == null) {
            this.queryParamMatchRules = EnumSet.noneOf(HttpJsonTranscodingQueryParamMatchRule.class);
        }
        this.queryParamMatchRules.addAll(ImmutableList.copyOf(queryParamMatchRules));
        return this;
    }

    /**
     * Sets an error handler which handles an exception raised while serving a gRPC request transcoded from
     * an HTTP/JSON request. By default, {@link UnframedGrpcErrorHandler#ofJson()} would be set.
     */
    @UnstableApi
    public HttpJsonTranscodingOptionsBuilder errorHandler(UnframedGrpcErrorHandler errorHandler) {
        requireNonNull(errorHandler, "errorHandler");
        this.errorHandler = errorHandler;
        return this;
    }

    /**
     * Returns a newly created {@link HttpJsonTranscodingOptions}.
     */
    public HttpJsonTranscodingOptions build() {
        final Set<HttpJsonTranscodingQueryParamMatchRule> matchRules;
        if (queryParamMatchRules == null) {
            matchRules = DEFAULT_QUERY_PARAM_MATCH_RULES;
        } else {
            matchRules = Sets.immutableEnumSet(queryParamMatchRules);
        }
        return new DefaultHttpJsonTranscodingOptions(matchRules, errorHandler);
    }
}
