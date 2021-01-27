/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.internal.client.grpc;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.client.grpc.GrpcClientUtil.maxInboundMessageSizeBytes;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.grpc.GrpcClientOptions;
import com.linecorp.armeria.client.grpc.GrpcClientStubFactory;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.Unwrappable;

import io.grpc.Channel;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.AbstractStub;

/**
 * A {@link DecoratingClientFactory} that creates a gRPC client.
 */
final class GrpcClientFactory extends DecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            Arrays.stream(SessionProtocol.values())
                  .flatMap(p -> GrpcSerializationFormats.values()
                                                        .stream()
                                                        .map(f -> Scheme.of(f, p)))
                  .collect(toImmutableSet());

    private static final List<GrpcClientStubFactory> clientStubFactories = ImmutableList.copyOf(
            ServiceLoader.load(GrpcClientStubFactory.class,
                               GrpcClientStubFactory.class.getClassLoader()));

    /**
     * Creates a new instance from the specified {@link ClientFactory} that supports the "none+http" scheme.
     *
     * @throws IllegalArgumentException if the specified {@link ClientFactory} does not support HTTP
     */
    GrpcClientFactory(ClientFactory httpClientFactory) {
        super(httpClientFactory);
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        validateParams(params);

        final Scheme scheme = params.scheme();
        final Class<?> clientType = params.clientType();
        final ClientOptions options = params.options();
        final SerializationFormat serializationFormat = scheme.serializationFormat();

        GrpcClientStubFactory clientStubFactory = options.get(GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY);
        ServiceDescriptor serviceDescriptor = null;

        if (clientStubFactory == NullGrpcClientStubFactory.INSTANCE) {
            for (GrpcClientStubFactory stubFactory : clientStubFactories) {
                serviceDescriptor = stubFactory.findServiceDescriptor(clientType);
                if (serviceDescriptor != null) {
                    clientStubFactory = stubFactory;
                    break;
                }
            }
        } else {
            serviceDescriptor = clientStubFactory.findServiceDescriptor(clientType);
        }

        if (serviceDescriptor == null) {
            throw newUnknownClientTypeException(clientType);
        }

        final ClientBuilderParams newParams =
                addTrailersExtractor(params, options, serializationFormat);
        final HttpClient httpClient = newHttpClient(newParams);

        final GrpcJsonMarshaller jsonMarshaller;
        if (GrpcSerializationFormats.isJson(serializationFormat)) {
            jsonMarshaller = options.get(GrpcClientOptions.GRPC_JSON_MARSHALLER_FACTORY)
                                    .apply(serviceDescriptor);
        } else {
            jsonMarshaller = null;
        }

        final ArmeriaChannel channel = new ArmeriaChannel(
                newParams,
                httpClient,
                meterRegistry(),
                scheme.sessionProtocol(),
                serializationFormat,
                jsonMarshaller);

        final Object clientStub = clientStubFactory.newClientStub(clientType, channel);
        requireNonNull(clientStub, "clientStubFactory.newClientStub() returned null");
        checkState(clientType.isAssignableFrom(clientStub.getClass()),
                   "Unexpected client stub type: %s (expected: %s or its subtype)",
                   clientStub.getClass().getName(), clientType.getName());
        return clientStub;
    }

    /**
     * Adds the {@link GrpcWebTrailersExtractor} if the specified {@link SerializationFormat} is a gRPC-Web and
     * {@link RetryingClient} exists in the {@link ClientDecoration}.
     */
    private static ClientBuilderParams addTrailersExtractor(
            ClientBuilderParams params,
            ClientOptions options,
            SerializationFormat serializationFormat) {
        if (!GrpcSerializationFormats.isGrpcWeb(serializationFormat)) {
            return params;
        }
        final ClientDecoration originalDecoration = options.decoration();
        final List<Function<? super HttpClient, ? extends HttpClient>> decorators =
                originalDecoration.decorators();

        boolean foundRetryingClient = false;
        final HttpClient noopClient = (ctx, req) -> null;
        for (Function<? super HttpClient, ? extends HttpClient> decorator: decorators) {
            final HttpClient decorated = decorator.apply(noopClient);
            if (decorated instanceof RetryingClient) {
                foundRetryingClient = true;
                break;
            }
        }
        if (!foundRetryingClient) {
            return params;
        }

        final GrpcWebTrailersExtractor webTrailersExtractor = new GrpcWebTrailersExtractor(
                maxInboundMessageSizeBytes(options),
                GrpcSerializationFormats.isGrpcWebText(serializationFormat));
        final ClientOptionsBuilder optionsBuilder = options.toBuilder();
        optionsBuilder.clearDecorators();
        optionsBuilder.decorator(webTrailersExtractor);

        decorators.forEach(optionsBuilder::decorator);

        return ClientBuilderParams.of(
                params.scheme(), params.endpointGroup(), params.absolutePathRef(),
                params.clientType(), optionsBuilder.build());
    }

    private static IllegalArgumentException newUnknownClientTypeException(Class<?> clientType) {
        return new IllegalArgumentException(
                "Unknown client type: " + clientType.getName() +
                " (expected: a gRPC client stub class, e.g. MyServiceGrpc.MyServiceStub)");
    }

    @Override
    public <T> T unwrap(Object client, Class<T> type) {
        final T unwrapped = super.unwrap(client, type);
        if (unwrapped != null) {
            return unwrapped;
        }

        if (!(client instanceof AbstractStub)) {
            return null;
        }

        final Channel ch = ((AbstractStub<?>) client).getChannel();
        if (!(ch instanceof Unwrappable)) {
            return null;
        }

        return ((Unwrappable) ch).as(type);
    }
}
