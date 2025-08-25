/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry;

import com.linecorp.armeria.common.Response;

public final class RetryConfigMappingBuilder<T extends Response> {
    private boolean perHost;
    private boolean perMethod;
    private boolean perPath;

    public RetryConfigMappingBuilder<T> perHost() {
        perHost = true;
        return this;
    }

    public RetryConfigMappingBuilder<T> perMethod() {
        perMethod = true;
        return this;
    }

    public RetryConfigMappingBuilder<T> perPath() {
        perPath = true;
        return this;
    }

    private boolean validateMappingKeys() {
        return perHost || perMethod || perPath;
    }

    public RetryConfigMapping<T> build(RetryConfigFactory<T> retryConfigFactory) {
        if (!validateMappingKeys()) {
            throw new IllegalStateException(
                    "A RetryConfigMapping created by this builder must be per host, method and/or path");
        }

        return new KeyedRetryConfigMapping<>(
                perHost, perMethod, perPath, retryConfigFactory
        );
    }
}
