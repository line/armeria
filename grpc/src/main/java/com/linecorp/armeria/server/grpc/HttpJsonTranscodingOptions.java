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
     * Returns whether to extract and register HTTP/JSON transcoding rules from {@code google.api.http}
     * annotations in proto descriptors. When {@code true}, methods with {@code google.api.http} options
     * are automatically registered as HTTP/JSON endpoints according to their annotation specifications.
     * This option is enabled by default.
     */
    boolean useHttpAnnotations();

    /**
     * Returns additional HTTP/JSON transcoding rules that supplement annotation-based rules. These rules
     * allow programmatic configuration of HTTP/JSON transcoding without modifying proto files. The rules are
     * processed regardless of the {@link #useHttpAnnotations()} setting. Returns an empty list by default.
     */
    List<HttpRule> additionalHttpRules();

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
