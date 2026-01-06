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

import java.util.List;
import java.util.Set;

import com.google.api.HttpRule;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * User provided options for customizing {@link HttpJsonTranscodingService}.
 */
@UnstableApi
public interface HttpJsonTranscodingOptions {

    /**
     * Returns a new {@link HttpJsonTranscodingOptionsBuilder}.
     */
    static HttpJsonTranscodingOptionsBuilder builder() {
        return new HttpJsonTranscodingOptionsBuilder();
    }

    /**
     * Returns the default {@link HttpJsonTranscodingOptions}.
     */
    static HttpJsonTranscodingOptions of() {
        return DefaultHttpJsonTranscodingOptions.DEFAULT;
    }

    /**
     * Returns whether to ignore {@code google.api.http} annotations defined in the Protobuf descriptors.
     *
     * <p>When {@code true}, automatic registration of endpoints from Proto annotations is disabled.
     * Only the rules specified via {@link #additionalHttpRules()} will be used.
     *
     * <p>When {@code false} (default), the service scans the Protobuf descriptors for
     * {@code google.api.http} annotations and registers them automatically, merging them with any
     * {@link #additionalHttpRules()} provided.
     *
     * <p>This option is useful when:
     * <ul>
     *     <li>You want to strictly control exposed endpoints (allowlist approach).</li>
     *     <li>You are using third-party Proto files and want to prevent them from unintentionally exposing
     *     HTTP routes.</li>
     * </ul>
     */
    boolean ignoreProtoHttpRule();

    /**
     * Returns the list of {@link HttpRule}s that are programmatically registered.
     *
     * <p>These rules are used to configure the {@link HttpJsonTranscodingService} without modifying Protobuf
     * files. They are processed in addition to any rules found in Proto annotations,
     * unless {@link #ignoreProtoHttpRule()} is enabled.
     */
    List<HttpRule> additionalHttpRules();

    /**
     * Returns the {@link HttpJsonTranscodingConflictStrategy} used to resolve conflicts when multiple rules
     * target the same gRPC method.
     *
     * <p>A conflict occurs when multiple {@link HttpRule}s (from annotations or
     * {@link #additionalHttpRules()}) attempt to configure the same gRPC method selector. This strategy
     * determines which rule is effectively applied.
     *
     * <p>Note: This strategy only handles configuration conflicts for the same method. It does <b>not</b>
     * resolve route conflicts where two <em>different</em> gRPC methods map to the exact same HTTP path.
     * Such conflicts are invalid and will raise an exception during registration.
     */
    HttpJsonTranscodingConflictStrategy conflictStrategy();

    /**
     * Returns the {@link HttpJsonTranscodingQueryParamMatchRule}s which is used to match fields in a
     * {@link Message} with query parameters.
     */
    Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules();

    /**
     * Return the {@link UnframedGrpcErrorHandler} which handles an exception raised while serving a gRPC
     * request transcoded from an HTTP/JSON request.
     */
    UnframedGrpcErrorHandler errorHandler();
}
