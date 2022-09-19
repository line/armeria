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
import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for {@link HttpJsonTranscodingOptions}.
 */
@UnstableApi
public class HttpJsonTranscodingOptionsBuilder {

    private static final EnumSet<HttpJsonTranscodingQueryParamNaming> DEFAULT_QUERY_PARAM_NAMING =
            EnumSet.of(HttpJsonTranscodingQueryParamNaming.ORIGINAL_FIELD);

    private UnframedGrpcErrorHandler errorHandler = UnframedGrpcErrorHandler.ofJson();

    @Nullable
    private Set<HttpJsonTranscodingQueryParamNaming> queryParamNamings;

    HttpJsonTranscodingOptionsBuilder() {}

    /**
     * Adds the specified {@link HttpJsonTranscodingQueryParamNaming} which is used to match {@link QueryParams}
     * of a {@link HttpRequest} with fields in a {@link Message}.
     * If not set, {@link HttpJsonTranscodingQueryParamNaming#ORIGINAL_FIELD} is used by default.
     */
    public HttpJsonTranscodingOptionsBuilder queryParamNaming(
            HttpJsonTranscodingQueryParamNaming... queryParamNamings) {
        requireNonNull(queryParamNamings, "queryParamNamings");
        queryParamNaming(ImmutableList.copyOf(queryParamNamings));
        return this;
    }

    /**
     * Adds the specified {@link HttpJsonTranscodingQueryParamNaming} which is used to match {@link QueryParams}
     * of a {@link HttpRequest} with fields in a {@link Message}.
     * If not set, {@link HttpJsonTranscodingQueryParamNaming#ORIGINAL_FIELD} is used by default.
     */
    public HttpJsonTranscodingOptionsBuilder queryParamNaming(
            Iterable<HttpJsonTranscodingQueryParamNaming> queryParamNamings) {
        requireNonNull(queryParamNamings, "queryParamNamings");
        checkArgument(!Iterables.isEmpty(queryParamNamings), "Can't set an empty queryParamNamings");
        if (this.queryParamNamings == null) {
            this.queryParamNamings = new HashSet<>();
        }
        this.queryParamNamings.addAll(ImmutableList.copyOf(queryParamNamings));
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
     * Returns a new created {@link HttpJsonTranscodingOptions}.
     */
    public HttpJsonTranscodingOptions build() {
        final EnumSet<HttpJsonTranscodingQueryParamNaming> paramNamings;
        if (queryParamNamings == null) {
            paramNamings = DEFAULT_QUERY_PARAM_NAMING;
        } else {
            paramNamings = EnumSet.copyOf(queryParamNamings);
        }
        return new DefaultHttpJsonTranscodingOptions(paramNamings, errorHandler);
    }
}
