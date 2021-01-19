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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.annotation.Nullable;

import io.grpc.Channel;

final class GrpcClientFactoryUtil {

    @Nullable
    static <T> Method findStubFactoryMethod(Class<T> clientType) {
        Method newStubMethod = null;
        for (Method method : clientType.getEnclosingClass().getDeclaredMethods()) {
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

    static IllegalStateException newClientStubCreationException(Throwable cause) {
        return new IllegalStateException("Could not create a gRPC stub through reflection.", cause);
    }

    private GrpcClientFactoryUtil() {}
}
