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

package com.linecorp.armeria.xds.filter;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents a resolved HTTP filter returned by {@link HttpFilterFactory#create}.
 * The framework skips this filter if {@link #disabled()} returns {@code true}.
 */
@UnstableApi
public interface XdsHttpFilter {

    /**
     * Returns {@code true} if this filter instance should be skipped.
     */
    default boolean disabled() {
        return false;
    }

    /**
     * Returns the {@link HttpPreprocessor} for downstream filter usage.
     */
    default HttpPreprocessor httpPreprocessor() {
        return PreClient::execute;
    }

    /**
     * Returns the {@link RpcPreprocessor} for downstream filter usage.
     */
    default RpcPreprocessor rpcPreprocessor() {
        return PreClient::execute;
    }

    /**
     * Returns the {@link DecoratingHttpClientFunction} for upstream filter usage.
     */
    default DecoratingHttpClientFunction httpDecorator() {
        return HttpClient::execute;
    }

    /**
     * Returns the {@link DecoratingRpcClientFunction} for upstream filter usage.
     */
    default DecoratingRpcClientFunction rpcDecorator() {
        return RpcClient::execute;
    }
}
