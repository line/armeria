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
package com.linecorp.armeria.internal.common.hessian;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * hessian method metadata.
 *
 * @author eisig
 */
public class HessianMethod {

    private final String name;

    private final Class<?> serviceType;

    private final Method method;

    private final Class<?> returnValueType;

    @Nullable
    private final Object implementation;

    private final ResponseType responseType;

    private final boolean blocking;

    HessianMethod(String name, Class<?> serviceType, Method method, Class<?> returnValueType,
                  @Nullable Object implementation, ResponseType responseType, boolean blocking) {
        this.name = name;
        this.serviceType = serviceType;
        this.method = method;
        this.returnValueType = returnValueType;
        this.implementation = implementation;
        this.responseType = responseType;
        this.blocking = blocking;
    }

    public static HessianMethod of(Class<?> serviceType, Method method, String name,
                                   @Nullable Object implementation) {
        return of(serviceType, method, name, implementation, true);
    }

    public static HessianMethod of(Class<?> serviceType, Method method, String name,
                                   @Nullable Object implementation, boolean blocking) {
        final ResponseType responseType = responseType(method);
        final Class<?> returnType = resolveReturnValueType(method, responseType);

        return new HessianMethod(name, serviceType, method, returnType, implementation, responseType, blocking);
    }

    private static Class<?> resolveReturnValueType(Method method, ResponseType responseType) {
        final Class<?> returnType;
        if (responseType == ResponseType.COMPLETION_STAGE) {
            final ParameterizedType parameterizedType = (ParameterizedType) method.getGenericReturnType();
            assert parameterizedType.getActualTypeArguments().length == 1;
            final Type argType = parameterizedType.getActualTypeArguments()[0];
            if (argType instanceof Class) {
                returnType = (Class<?>) argType;
            } else {
                if (argType instanceof ParameterizedType) {
                    returnType = (Class<?>) ((ParameterizedType) argType).getRawType();
                } else {
                    throw new IllegalStateException("unsupported return type " + argType);
                }
            }
        } else {
            returnType = method.getReturnType();
        }
        return returnType;
    }

    static ResponseType responseType(Method method) {
        final Class<?> returnType = method.getReturnType();
        if (CompletionStage.class.isAssignableFrom(returnType) && returnType.isAssignableFrom(
                CompletableFuture.class)) {
            return ResponseType.COMPLETION_STAGE;
        }
        return ResponseType.OTHER_OBJECTS;
    }

    public String getName() {
        return name;
    }

    public Class<?> getServiceType() {
        return serviceType;
    }

    public Method getMethod() {
        return method;
    }

    /**
     * The service Implement object, null when use in client side.
     */
    @Nullable
    public Object getImplementation() {
        return implementation;
    }

    /**
     * the return type for the method. if return type isCompletionStage, return the actualTypeArgument.
     */
    public Class<?> getReturnValueType() {
        return returnValueType;
    }

    public ResponseType getResponseType() {
        return responseType;
    }

    /**
     * use blocking executor or not. only for server side.
     */
    public boolean isBlocking() {
        return blocking;
    }

    public enum ResponseType {
        COMPLETION_STAGE, OTHER_OBJECTS
    }
}
