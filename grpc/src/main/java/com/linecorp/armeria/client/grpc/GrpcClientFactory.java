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
package com.linecorp.armeria.client.grpc;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.DefaultClientBuilderParams;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.grpc.GrpcJsonUtil;

import io.grpc.Channel;
import io.grpc.MethodDescriptor;
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

    private static final Consumer<MessageMarshaller.Builder> NO_OP = (unused) -> {};

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
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);
        final Endpoint endpoint = newEndpoint(uri);

        return newClient(uri, scheme, endpoint, clientType, options);
    }

    @Override
    public <T> T newClient(Scheme scheme, Endpoint endpoint, String path, Class<T> clientType,
                           ClientOptions options) {
        final URI uri = endpoint.toUri(scheme, path);

        return newClient(uri, scheme, endpoint, clientType, options);
    }

    private <T> T newClient(URI uri, Scheme scheme, Endpoint endpoint, Class<T> clientType,
                            ClientOptions options) {
        final SerializationFormat serializationFormat = scheme.serializationFormat();
        final Class<?> stubClass = clientType.getEnclosingClass();
        if (stubClass == null) {
            throw new IllegalArgumentException("Client type not a gRPC client stub class, " +
                                               "should be something like ServiceNameGrpc.ServiceNameXXStub: " +
                                               clientType);
        }
        final Method newStubMethod;
        final Method newBlockingStubMethod;
        final Method newFutureStubMethod;
        try {
            newStubMethod = stubClass.getDeclaredMethod("newStub", Channel.class);
            newBlockingStubMethod = stubClass.getDeclaredMethod("newBlockingStub", Channel.class);
            newFutureStubMethod = stubClass.getDeclaredMethod("newFutureStub", Channel.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Client type not a gRPC client stub class, " +
                                               "should be something like ServiceNameGrpc.ServiceNameXXStub: " +
                                               clientType, e);
        }
        final Method createClientMethod;
        if (newStubMethod.getReturnType() == clientType) {
            createClientMethod = newStubMethod;
        } else if (newBlockingStubMethod.getReturnType() == clientType) {
            createClientMethod = newBlockingStubMethod;
        } else if (newFutureStubMethod.getReturnType() == clientType) {
            createClientMethod = newFutureStubMethod;
        } else {
            throw new IllegalArgumentException("Client type not a gRPC client stub class, " +
                                               "should be something like ServiceNameGrpc.ServiceNameXXStub: " +
                                               clientType);
        }

        final Client<HttpRequest, HttpResponse> httpClient = newHttpClient(uri, scheme, options);

        final MessageMarshaller jsonMarshaller =
                GrpcSerializationFormats.isJson(serializationFormat) ?
                GrpcJsonUtil.jsonMarshaller(
                        stubMethods(stubClass),
                        options.getOrElse(GrpcClientOptions.JSON_MARSHALLER_CUSTOMIZER, NO_OP)) : null;
        final ArmeriaChannel channel = new ArmeriaChannel(
                new DefaultClientBuilderParams(this,
                                               Strings.isNullOrEmpty(uri.getPath()) ? rootPathUri(uri) : uri,
                                               clientType, options),
                httpClient,
                meterRegistry(),
                scheme.sessionProtocol(),
                endpoint,
                serializationFormat,
                jsonMarshaller);

        try {
            // Verified createClientMethod.getReturnType == clientType
            @SuppressWarnings("unchecked")
            final T stub = (T) createClientMethod.invoke(null, channel);
            return stub;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not create stub through reflection.", e);
        }
    }

    @Override
    public <T> Optional<ClientBuilderParams> clientBuilderParams(T client) {
        if (!(client instanceof AbstractStub)) {
            return Optional.empty();
        }
        final AbstractStub<?> stub = (AbstractStub<?>) client;
        if (!(stub.getChannel() instanceof ArmeriaChannel)) {
            return Optional.empty();
        }
        return Optional.of((ClientBuilderParams) stub.getChannel());
    }

    private static List<MethodDescriptor<?, ?>> stubMethods(Class<?> stubClass) {
        final Method getServiceDescriptorMethod;
        try {
            getServiceDescriptorMethod = stubClass.getDeclaredMethod("getServiceDescriptor");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Could not find getServiceDescriptor on gRPC client stub.");
        }
        final ServiceDescriptor descriptor;
        try {
            descriptor = (ServiceDescriptor) getServiceDescriptorMethod.invoke(stubClass);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not invoke getServiceDescriptor on a gRPC client stub.");
        }
        return ImmutableList.copyOf(descriptor.getMethods());
    }

    private Client<HttpRequest, HttpResponse> newHttpClient(URI uri, Scheme scheme, ClientOptions options) {
        try {
            @SuppressWarnings("unchecked")
            final Client<HttpRequest, HttpResponse> client = delegate().newClient(
                    new URI(Scheme.of(SerializationFormat.NONE, scheme.sessionProtocol()).uriText(),
                            uri.getAuthority(), null, null, null),
                    Client.class,
                    options);
            return client;
        } catch (URISyntaxException e) {
            throw new Error(e); // Should never happen.
        }
    }

    private static URI rootPathUri(URI uri) {
        try {
            return new URI(uri.getScheme(), uri.getRawAuthority(), "/", uri.getRawQuery(),
                           uri.getRawFragment());
        } catch (URISyntaxException e) {
            throw new Error(e); // Should never happen.
        }
    }
}
