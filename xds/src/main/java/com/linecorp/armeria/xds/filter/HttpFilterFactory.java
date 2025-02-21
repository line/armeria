/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.filter;

import com.google.protobuf.Message;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreprocessor;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * An {@link HttpFilterFactory} is a factory for creating a decorator implementation equivalent to
 * an {@link HttpFilter}.
 */
public interface HttpFilterFactory<T extends Message> {

    /**
     * Generates a {@link RpcPreprocessor} which acts as a downstream {@link HttpFilter} when
     * registered in {@link HttpConnectionManager#getHttpFiltersList()}.
     */
    default RpcPreprocessor rpcPreprocessor(T config) {
        return PreClient::execute;
    }

    /**
     * Generates a {@link HttpPreprocessor} which acts as a downstream {@link HttpFilter} when
     * registered in {@link HttpConnectionManager#getHttpFiltersList()}.
     */
    default HttpPreprocessor httpPreprocessor(T config) {
        return PreClient::execute;
    }

    /**
     * Generates a {@link DecoratingHttpClientFunction} which acts as an upstream {@link HttpFilter} when
     * registered in {@link Router#getUpstreamHttpFiltersList()}.
     * Unlike decorators added to clients, this decorator will not be invoked for RPC clients.
     */
    default DecoratingHttpClientFunction httpDecorator(T config) {
        return HttpClient::execute;
    }

    /**
     * Generates a {@link DecoratingRpcClientFunction} which acts as an upstream {@link HttpFilter} when
     * registered in {@link Router#getUpstreamHttpFiltersList()}.
     */
    default DecoratingRpcClientFunction rpcDecorator(T config) {
        return RpcClient::execute;
    }

    /**
     * The class type of the filter configuration represented by {@link HttpFilter#getTypedConfig()}.
     */
    Class<T> configClass();

    /**
     * The default configuration to be used if an appropriate configuration cannot be found.
     */
    T defaultConfig();

    /**
     * The filter name that should be equivalent to {@link HttpFilter#getName()}.
     */
    String filterName();
}
