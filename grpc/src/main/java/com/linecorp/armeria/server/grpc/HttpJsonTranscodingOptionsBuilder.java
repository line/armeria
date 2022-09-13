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

/**
 * builder for {@link HttpJsonTranscodingOptions}.
 */
public class HttpJsonTranscodingOptionsBuilder {

    private boolean camelCaseQueryParams;

    HttpJsonTranscodingOptionsBuilder() {}

    /**
     * enables camelCase query parameters for Http Json Transcoding endpoints.
     * provided by {@link HttpJsonTranscodingService}.
     */
    public HttpJsonTranscodingOptionsBuilder useCamelCaseQueryParams() {
        this.camelCaseQueryParams = true;
        return this;
    }

    /**
     * builds {@link HttpJsonTranscodingOptions}.
     */
    public HttpJsonTranscodingOptions build() {
        return HttpJsonTranscodingOptions.of(camelCaseQueryParams);
    }
}
