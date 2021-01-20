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

package com.linecorp.armeria.internal.client.grpc;

import static com.linecorp.armeria.internal.client.grpc.GrpcClientFactoryUtil.newClientStubCreationException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.grpc.GrpcClientStubFactory;

import io.grpc.Channel;
import io.grpc.ServiceDescriptor;

/**
 * A gRPC client stub factory for <a href="https://github.com/grpc/grpc-kotlin">gRPC-Kotlin</a>.
 */
public final class KotlinGrpcClientStubFactory implements GrpcClientStubFactory {

    @Nullable
    @Override
    public ServiceDescriptor findServiceDescriptor(Class<?> clientType) {
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

        return null;
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

    private static Class<?> generatedStub(Annotation annotation, Method valueMethod) {
        try {
            return (Class<?>) valueMethod.invoke(annotation, null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not find a gRPC Kotlin generated client stub.");
        }
    }

    @Override
    public Object newClientStub(Class<?> clientType, Channel channel) {
        Constructor<?> constructor = null;

        for (Constructor<?> ctor : clientType.getConstructors()) {
            final Class<?>[] methodParameterTypes = ctor.getParameterTypes();
            if (methodParameterTypes.length == 1 && methodParameterTypes[0] == Channel.class) {
                // Must have a single `Channel` parameter.
                constructor = ctor;
                break;
            }
        }

        if (constructor == null) {
            throw new IllegalStateException(
                    "Could not find a constructor on a gRPC Kotlin client stub: " + clientType.getName());
        }

        try {
            return constructor.newInstance(channel);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw newClientStubCreationException(e);
        }
    }
}
