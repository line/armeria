/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.splitPathAndQuery;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UserClient;
import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultTHttpClient extends UserClient<RpcRequest, RpcResponse> implements THttpClient {

    DefaultTHttpClient(ClientBuilderParams params, Client<RpcRequest, RpcResponse> delegate,
                       MeterRegistry meterRegistry, SessionProtocol sessionProtocol, Endpoint endpoint) {
        super(params, delegate, meterRegistry, sessionProtocol, endpoint);
    }

    @Override
    public RpcResponse execute(String path, Class<?> serviceType, String method, Object... args) {
        return execute0(path, serviceType, null, method, args);
    }

    @Override
    public RpcResponse executeMultiplexed(
            String path, Class<?> serviceType, String serviceName, String method, Object... args) {
        requireNonNull(serviceName, "serviceName");
        return execute0(path, serviceType, serviceName, method, args);
    }

    private RpcResponse execute0(
            String path, Class<?> serviceType, @Nullable String serviceName, String method, Object[] args) {

        path = concatPaths(uri().getRawPath(), path);
        final String[] pathAndQuery = splitPathAndQuery(path);
        if (pathAndQuery == null) {
            return RpcResponse.ofFailure(new IllegalArgumentException("invalid path: " + path));
        }

        final RpcRequest call = RpcRequest.of(serviceType, method, args);
        return execute(HttpMethod.POST, pathAndQuery[0], null, serviceName, call, DefaultRpcResponse::new);
    }
}
