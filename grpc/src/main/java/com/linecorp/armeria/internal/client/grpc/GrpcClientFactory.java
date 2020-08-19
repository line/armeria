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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.client.grpc.GrpcClientUtil.maxInboundMessageSizeBytes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.grpc.GrpcClientOptions;
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
        final Class<?> enclosingClass = clientType.getEnclosingClass();
        if (enclosingClass == null) {
            throw newUnknownClientTypeException(clientType);
        }

        final ClientBuilderParams newParams =
                addTrailersExtractor(params, options, serializationFormat);
        final HttpClient httpClient = newHttpClient(newParams);

        final GrpcJsonMarshaller jsonMarshaller;
        if (GrpcSerializationFormats.isJson(serializationFormat)) {
            jsonMarshaller = options.get(GrpcClientOptions.GRPC_JSON_MARSHALLER_FACTORY)
                                    .apply(getServiceDescriptor(clientType));
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

        final Method stubFactoryMethod = findStubFactoryMethod(clientType, enclosingClass);
        try {
            // Verified stubFactoryMethod.getReturnType() == clientType in findStubFactoryMethod().
            if (stubFactoryMethod != null) {
                return stubFactoryMethod.invoke(null, channel);
            } else {
                final Constructor<?> stubConstructor = findStubConstructor(clientType);
                if (stubConstructor == null) {
                    throw newUnknownClientTypeException(clientType);
                }
                return stubConstructor.newInstance(channel);
            }
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
            throw new IllegalStateException("Could not create a gRPC stub through reflection.", e);
        }
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

    @Nullable
    private static <T> Method findStubFactoryMethod(Class<T> clientType, Class<?> enclosingClass) {
        Method newStubMethod = null;
        for (Method method : enclosingClass.getDeclaredMethods()) {
            final int methodModifiers = method.getModifiers();
            if (!(Modifier.isPublic(methodModifiers) && Modifier.isStatic(methodModifiers))) {
                // Must be public and static.
                continue;
            }

            final String methodName = method.getName();
            if (!methodName.toLowerCase().endsWith("stub")) {
                // Must be named as `*[sS]tub()`.
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
        return newStubMethod;
    }

    @Nullable
    private static <T> Constructor<?> findStubConstructor(Class<T> clientType) {
        if (!clientType.getName().endsWith("CoroutineStub")) {
            return null;
        }

        for (Constructor<?> constructor : clientType.getConstructors()) {
            final Class<?>[] methodParameterTypes = constructor.getParameterTypes();
            if (methodParameterTypes.length == 1 && methodParameterTypes[0] == Channel.class) {
                // Must have a single `Channel` parameter.
                return constructor;
            }
        }
        return null;
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

    private static ServiceDescriptor getServiceDescriptor(Class<?> clientType) {
        if (clientType.getName().endsWith("CoroutineStub")) {
            final Annotation annotation = stubForAnnotation(clientType);
            try {
                final Method valueMethod = annotation.annotationType().getDeclaredMethod("value", null);
                final Class<?> generatedStub = generatedStub(annotation, valueMethod);
                final Method getServiceDescriptor =
                        generatedStub.getDeclaredMethod("getServiceDescriptor", null);
                try {
                    return (ServiceDescriptor) getServiceDescriptor.invoke(generatedStub);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException(
                            "Could not invoke getServiceDescriptor on a gRPC Kotlin client stub.");
                }
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Could not find value getter on StubFor annotation.");
            }
        }

        final Class<?> stubClass = clientType.getEnclosingClass();
        Method getServiceDescriptorMethod;
        try {
            getServiceDescriptorMethod = stubClass.getDeclaredMethod("getServiceDescriptor");
        } catch (NoSuchMethodException e) {
            try {
                // Fallback for ScalaPB
                getServiceDescriptorMethod = stubClass.getDeclaredMethod("SERVICE");
            } catch (NoSuchMethodException noSuchMethodException) {
                throw new IllegalStateException(
                        "Could not find a io.grpc.ServiceDescriptor on a gRPC client stub.");
            }
        }
        try {
            return (ServiceDescriptor) getServiceDescriptorMethod.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not invoke getServiceDescriptor on a gRPC client stub.");
        }
    }

    private static Class<?> generatedStub(Annotation annotation, Method valueMethod) {
        try {
            return (Class<?>) valueMethod.invoke(annotation, null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not find a gRPC Kotlin generated client stub.");
        }
    }

    private static Annotation stubForAnnotation(Class<?> clientType) {
        try {
            @SuppressWarnings("unchecked")
            final Class<Annotation> annotationClass =
                    (Class<Annotation>) Class.forName("io.grpc.kotlin.StubFor");
            final Annotation annotation = clientType.getAnnotation(annotationClass);
            if (annotation == null) {
                throw new IllegalStateException(
                        "Could not find StubFor annotation on a gRPC Kotlin client stub.");
            }
            return annotation;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not find StubFor annotation on a gRPC Kotlin client stub.");
        }
    }
}
