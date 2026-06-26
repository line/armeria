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
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.JSON_NAME;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.ORIGINAL_FIELD;
import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.google.api.HttpRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
public final class HttpJsonTranscodingOptionsBuilder {

    /**
     * Evaluates json_name first, then original field.
     */
    private static final EnumSet<HttpJsonTranscodingQueryParamMatchRule> DEFAULT_QUERY_PARAM_MATCH_RULES =
            EnumSet.of(JSON_NAME, ORIGINAL_FIELD);

    private final ImmutableList.Builder<HttpRule> additionalHttpRules = ImmutableList.builder();

    private boolean ignoreProtoHttpRule;

    private HttpJsonTranscodingConflictStrategy conflictStrategy = HttpJsonTranscodingConflictStrategy.strict();

    private UnframedGrpcErrorHandler errorHandler = UnframedGrpcErrorHandler.ofJson();

    @Nullable
    private Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules;

    HttpJsonTranscodingOptionsBuilder() {}

    /**
     * A copy constructor.
     */
    HttpJsonTranscodingOptionsBuilder(HttpJsonTranscodingOptions options) {
        ignoreProtoHttpRule(options.ignoreProtoHttpRule());
        additionalHttpRules(options.additionalHttpRules());
        conflictStrategy(options.conflictStrategy());
        queryParamMatchRules(options.queryParamMatchRules());
        errorHandler(options.errorHandler());
    }

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
     * Sets whether to ignore {@code google.api.http} annotations defined in the Protobuf descriptors.
     *
     * <p>When {@code true}, automatic registration of endpoints from Proto annotations is
     * disabled. Only the rules specified via {@link #additionalHttpRules(Iterable)} will be used.
     *
     * <p>When {@code false} (default), the service scans the Protobuf descriptors for
     * {@code google.api.http} annotations and registers them automatically, merging them
     * with any {@link #additionalHttpRules(Iterable)} provided.
     *
     * <p>This option is useful when:
     * <ul>
     *     <li>You want to strictly control exposed endpoints (allowlist approach).</li>
     *     <li>You are using third-party Proto files and want to prevent them from unintentionally exposing
     *     HTTP routes.</li>
     * </ul>
     */
    public HttpJsonTranscodingOptionsBuilder ignoreProtoHttpRule(boolean ignoreProtoHttpRule) {
        this.ignoreProtoHttpRule = ignoreProtoHttpRule;
        return this;
    }

    /**
     * Adds additional HTTP/JSON transcoding rules that supplement annotation-based rules. These rules allow
     * programmatic configuration of HTTP/JSON transcoding without modifying proto files.
     *
     * <p>These rules are processed in addition to any rules found in Proto annotations, unless
     * {@link #ignoreProtoHttpRule(boolean)} is set to {@code true}.
     */
    public HttpJsonTranscodingOptionsBuilder additionalHttpRules(HttpRule... rules) {
        requireNonNull(rules, "rules");
        return additionalHttpRules(ImmutableList.copyOf(rules));
    }

    /**
     * Adds additional HTTP/JSON transcoding rules that supplement annotation-based rules. These rules allow
     * programmatic configuration of HTTP/JSON transcoding without modifying proto files.
     *
     * <p>These rules are processed in addition to any rules found in Proto annotations, unless
     * {@link #ignoreProtoHttpRule(boolean)} is set to {@code true}.
     */
    public HttpJsonTranscodingOptionsBuilder additionalHttpRules(Iterable<HttpRule> rules) {
        requireNonNull(rules, "rules");
        additionalHttpRules.addAll(rules);
        return this;
    }

    /**
     * Sets the {@link HttpJsonTranscodingConflictStrategy} to use when multiple {@link HttpRule}s target the
     * same gRPC method.
     *
     * <p>This strategy determines which rule is applied when a gRPC method is configured multiple times
     * (e.g. via both Proto annotations and programmatic configuration).
     * By default, {@link HttpJsonTranscodingConflictStrategy#strict()} is used.
     */
    public HttpJsonTranscodingOptionsBuilder conflictStrategy(
            HttpJsonTranscodingConflictStrategy conflictStrategy) {
        requireNonNull(conflictStrategy, "conflictStrategy");
        this.conflictStrategy = conflictStrategy;
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
        final List<HttpRule> rules = additionalHttpRules.build();
        final Set<HttpJsonTranscodingQueryParamMatchRule> matchRules;
        if (queryParamMatchRules == null) {
            matchRules = DEFAULT_QUERY_PARAM_MATCH_RULES;
        } else {
            if (queryParamMatchRules.size() == 1 && queryParamMatchRules.contains(JSON_NAME)) {
                // If neither LOWER_CAMEL_CASE nor ORIGINAL_FIELD is set, add ORIGINAL_FIELD by default.
                final Set<HttpJsonTranscodingQueryParamMatchRule> newMatchRules =
                        ImmutableSet.<HttpJsonTranscodingQueryParamMatchRule>builder()
                                    .addAll(queryParamMatchRules)
                                    .add(ORIGINAL_FIELD)
                                    .build();
                matchRules = newMatchRules;
            } else {
                matchRules = ImmutableSet.copyOf(queryParamMatchRules);
            }
        }
        return new DefaultHttpJsonTranscodingOptions(ignoreProtoHttpRule, rules, conflictStrategy,
                                                     matchRules, errorHandler);
    }
}
