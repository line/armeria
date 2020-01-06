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
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.Unwrappable;
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

    private static final Consumer<MessageMarshaller.Builder> NO_OP = unused -> {};

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
    public <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                           ClientOptions options) {
        final URI uri = endpoint.toUri(scheme, path);

        return newClient(uri, scheme, endpoint, clientType, options);
    }

    private <T> T newClient(URI uri, Scheme scheme, Endpoint endpoint, Class<T> clientType,
                            ClientOptions options) {
        final SerializationFormat serializationFormat = scheme.serializationFormat();
        final Class<?> stubClass = clientType.getEnclosingClass();
        if (stubClass == null) {
            return reject(clientType);
        }

        final Method stubFactoryMethod = findStubFactoryMethod(clientType, stubClass);

        final MessageMarshaller jsonMarshaller =
                GrpcSerializationFormats.isJson(serializationFormat) ?
                GrpcJsonUtil.jsonMarshaller(
                        stubMethods(stubClass),
                        options.getOrElse(GrpcClientOptions.JSON_MARSHALLER_CUSTOMIZER, NO_OP)) : null;

        final ArmeriaChannel channel = new ArmeriaChannel(
                ClientBuilderParams.of(this,
                                       Strings.isNullOrEmpty(uri.getPath()) ? rootPathUri(uri) : uri,
                                       clientType, options),
                newHttpClient(uri, scheme, options),
                meterRegistry(),
                scheme.sessionProtocol(),
                endpoint,
                serializationFormat,
                jsonMarshaller);

        try {
            // Verified stubFactoryMethod.getReturnType() == clientType in findStubFactoryMethod().
            @SuppressWarnings("unchecked")
            final T stub = (T) stubFactoryMethod.invoke(null, channel);
            return stub;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not create a gRPC stub through reflection.", e);
        }
    }

    private static <T> Method findStubFactoryMethod(Class<T> clientType, Class<?> stubClass) {
        Method newStubMethod = null;
        for (Method method : stubClass.getDeclaredMethods()) {
            final int methodModifiers = method.getModifiers();
            if (!(Modifier.isPublic(methodModifiers) && Modifier.isStatic(methodModifiers))) {
                // Must be public and static.
                continue;
            }

            final String methodName = method.getName();
            if (!(methodName.startsWith("new") && methodName.endsWith("Stub"))) {
                // Must be named as `new*Stub()`.
                continue;
            }

            final Class<?>[] methodParameterTypes = method.getParameterTypes();
            if (!(methodParameterTypes.length == 1 && methodParameterTypes[0] == Channel.class)) {
                // Must have a single `Channel` parameter.
                continue;
            }

            if (!clientType.isAssignableFrom(method.getReturnType())) {
                // Must return a stub compatible with `clientType`.
                continue;
            }

            newStubMethod = method;
            break;
        }

        if (newStubMethod == null) {
            return reject(clientType);
        }
        return newStubMethod;
    }

    private static <T> T reject(Class<?> clientType) {
        throw new IllegalArgumentException(
                "Unknown client type: " + clientType.getName() +
                " (expected: a gRPC client stub class, e.g. MyServiceGrpc.MyServiceStub)");
    }

    @Override
    public <T> Optional<T> unwrap(Object client, Class<T> type) {
        final Optional<T> unwrapped = super.unwrap(client, type);
        if (unwrapped.isPresent()) {
            return unwrapped;
        }

        if (!(client instanceof AbstractStub)) {
            return Optional.empty();
        }

        final Channel ch = ((AbstractStub<?>) client).getChannel();
        if (!(ch instanceof Unwrappable)) {
            return Optional.empty();
        }

        return ((Unwrappable) ch).as(type);
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

    private HttpClient newHttpClient(URI uri, Scheme scheme, ClientOptions options) {
        try {
            return delegate().newClient(
                    new URI(Scheme.of(SerializationFormat.NONE, scheme.sessionProtocol()).uriText(),
                            uri.getAuthority(), null, null, null),
                    HttpClient.class, options);
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
