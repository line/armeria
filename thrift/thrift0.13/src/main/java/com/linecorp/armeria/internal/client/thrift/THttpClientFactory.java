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

import java.lang.reflect.Proxy;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.thrift.THttpClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

/**
 * A {@link DecoratingClientFactory} that creates a Thrift-over-HTTP client.
 */
final class THttpClientFactory extends DecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES;

    static {
        final ImmutableSet.Builder<Scheme> builder = ImmutableSet.builder();
        for (SessionProtocol p : SessionProtocol.values()) {
            for (SerializationFormat f : ThriftSerializationFormats.values()) {
                builder.add(Scheme.of(f, p));
            }
        }
        SUPPORTED_SCHEMES = builder.build();
    }

    /**
     * Creates a new instance from the specified {@link ClientFactory} that supports the "none+http" scheme.
     *
     * @throws IllegalArgumentException if the specified {@link ClientFactory} does not support HTTP
     */
    THttpClientFactory(ClientFactory httpClientFactory) {
        super(httpClientFactory);
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        validateParams(params);

        final Class<?> clientType = params.clientType();
        final ClientOptions options = params.options();
        final RpcClient delegate = options.decoration().rpcDecorate(
                new THttpClientDelegate(newHttpClient(params), options, params.scheme().serializationFormat()));

        if (clientType == THttpClient.class) {
            // Create a THttpClient with path.
            return new DefaultTHttpClient(params, delegate, meterRegistry());
        }

        // Create a THttpClient without path.
        final ClientBuilderParams delegateParams = params.paramsBuilder()
                                                         .absolutePathRef("/")
                                                         .clientType(THttpClient.class)
                                                         .build();

        final THttpClient thriftClient = new DefaultTHttpClient(delegateParams, delegate, meterRegistry());

        return Proxy.newProxyInstance(
                clientType.getClassLoader(),
                new Class<?>[] { clientType },
                new THttpClientInvocationHandler(params, thriftClient));
    }

    @Override
    public ClientBuilderParams validateParams(ClientBuilderParams params) {
        if (params.scheme().sessionProtocol() == SessionProtocol.UNDEFINED &&
            params.options().clientPreprocessors().rpcPreprocessors().isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one rpcPreprocessor must be specified for rpc-based clients " +
                    "with sessionProtocol '" + params.scheme().sessionProtocol() + "'.");
        }
        return super.validateParams(params);
    }
}
