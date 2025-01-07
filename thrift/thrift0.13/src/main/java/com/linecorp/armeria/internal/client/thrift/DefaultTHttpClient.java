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

package com.linecorp.armeria.internal.client.thrift;

import static com.linecorp.armeria.internal.client.thrift.THttpClientDelegate.decodeException;
import static java.util.Objects.requireNonNull;

import org.apache.thrift.transport.TTransportException;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcPreClient;
import com.linecorp.armeria.client.UserClient;
import com.linecorp.armeria.client.thrift.THttpClient;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.internal.client.TailPreClient;
import com.linecorp.armeria.internal.common.RequestTargetCache;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultTHttpClient extends UserClient<RpcRequest, RpcResponse> implements THttpClient {

    private static final RequestOptions UNARY_REQUEST_OPTIONS =
            RequestOptions.builder()
                          .exchangeType(ExchangeType.UNARY)
                          .build();

    private final RpcPreClient rpcPreprocessor;

    DefaultTHttpClient(ClientBuilderParams params, RpcClient delegate, MeterRegistry meterRegistry) {
        super(params, delegate, meterRegistry, RpcResponse::from,
              (ctx, cause) -> RpcResponse.ofFailure(decodeException(cause, null)));
        rpcPreprocessor = TailPreClient.ofRpc(unwrap(), futureConverter(), errorResponseFactory());
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

        if (serviceName != null) {
            path = path + '#' + serviceName;
        }

        final RequestTarget reqTarget = RequestTarget.forClient(path, uri().getRawPath());
        if (reqTarget == null) {
            return RpcResponse.ofFailure(new TTransportException(
                    new IllegalArgumentException("invalid path: " + path)));
        }

        // A thrift path is always good to cache as it cannot have non-fixed parameters.
        RequestTargetCache.putForClient(path, reqTarget);

        final RpcRequest call = RpcRequest.of(serviceType, method, args);
        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                scheme().sessionProtocol(), null, HttpMethod.POST, call, reqTarget, endpointGroup(),
                UNARY_REQUEST_OPTIONS, options());
        final RpcPreClient execution = options().clientPreprocessors().rpcDecorate(rpcPreprocessor);
        return ClientUtil.executeWithFallback(execution, ctx, call, errorResponseFactory());
    }

    @Override
    public RpcClient unwrap() {
        return (RpcClient) super.unwrap();
    }
}
