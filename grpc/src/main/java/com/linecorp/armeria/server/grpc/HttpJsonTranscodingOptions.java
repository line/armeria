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
    static HttpJsonTranscodingOptions ofDefault() {
        return DefaultHttpJsonTranscodingOptions.DEFAULT;
    }

    /**
     * Returns whether to use a field name converted into lowerCamelCase to match query parameters.
     */
    boolean useCamelCaseQueryParams();

    /**
     * Returns whether to use the original field name in .proto file to match query parameters.
     */
    boolean useProtoFieldNameQueryParams();
}
