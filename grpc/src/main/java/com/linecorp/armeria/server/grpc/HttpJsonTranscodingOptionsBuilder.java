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

/**
 * builder for {@link HttpJsonTranscodingOptions}.
 */
public class HttpJsonTranscodingOptionsBuilder {

    private boolean useCamelCaseQueryParams;
    private boolean useProtoFieldNameQueryParams = true;

    HttpJsonTranscodingOptionsBuilder() {}

    /**
     * Sets whether to use lowerCamelCase query parameters for HTTP-JSON Transcoding endpoints.
     * This option is disabled by default.
     *
     * <p>Note that this option is added as an OR condition without disabling
     * {@link #useProtoFieldNameQueryParams(boolean)}. If {@link #useCamelCaseQueryParams(boolean)} and
     * {@link #useProtoFieldNameQueryParams(boolean)} are set to {@code true}, both lowerCamelCase and the
     * original field name are considered valid inputs.
     */
    public HttpJsonTranscodingOptionsBuilder useCamelCaseQueryParams(boolean useCamelCaseQueryParams) {
        checkArgument(useCamelCaseQueryParams || useProtoFieldNameQueryParams,
                      "Can't disable both useCamelCaseQueryParams and useProtoFieldNameQueryParams");
        this.useCamelCaseQueryParams = useCamelCaseQueryParams;
        return this;
    }

    /**
     * Sets whether to use the original field name in .proto file to match query parameters.
     * This option is enabled by default.
     *
     * <p>Note that this option is added as an OR condition without disabling
     * {@link #useCamelCaseQueryParams(boolean)}. If {@link #useProtoFieldNameQueryParams(boolean)} and
     * {@link #useCamelCaseQueryParams(boolean)} are set to {@code true}, both lowerCamelCase and the
     * original field name are considered valid inputs.
     */
    public HttpJsonTranscodingOptionsBuilder useProtoFieldNameQueryParams(
            boolean useProtoFieldNameQueryParams) {
        checkArgument(useProtoFieldNameQueryParams || useCamelCaseQueryParams,
                      "Can't disable both useProtoFieldNameQueryParams and useCamelCaseQueryParams");
        this.useProtoFieldNameQueryParams = useProtoFieldNameQueryParams;
        return this;
    }

    /**
     * builds {@link HttpJsonTranscodingOptions}.
     */
    public HttpJsonTranscodingOptions build() {
        return new DefaultHttpJsonTranscodingOptions(useCamelCaseQueryParams, useProtoFieldNameQueryParams);
    }
}
