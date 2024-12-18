/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.client.grpc.protocol;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.util.Arrays;
import java.util.Set;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.protocol.UnaryGrpcClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;

/**
 * A {@link DecoratingClientFactory} that creates a {@link UnaryGrpcClient}.
 */
final class UnaryGrpcClientFactory extends DecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            Arrays.stream(SessionProtocol.values())
                  .flatMap(p -> UnaryGrpcSerializationFormats.values()
                                                             .stream()
                                                             .map(f -> Scheme.of(f, p)))
                  .collect(toImmutableSet());

    /**
     * Creates a new instance from the specified {@link ClientFactory} that supports the "none+http" scheme.
     */
    UnaryGrpcClientFactory(ClientFactory httpClientFactory) {
        super(httpClientFactory);
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public boolean isClientTypeSupported(Class<?> clientType) {
        return clientType == UnaryGrpcClient.class;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        final Scheme scheme = params.scheme();
        final SerializationFormat serializationFormat = scheme.serializationFormat();
        final ClientBuilderParams newParams = params.paramsBuilder()
                                                    .serializationFormat(SerializationFormat.NONE)
                                                    .clientType(WebClient.class)
                                                    .build();
        final WebClient webClient = (WebClient) unwrap().newClient(newParams);
        return new UnaryGrpcClient(webClient, serializationFormat);
    }
}
