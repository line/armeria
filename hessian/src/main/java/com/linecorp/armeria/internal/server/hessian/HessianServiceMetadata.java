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

package com.linecorp.armeria.internal.server.hessian;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.caucho.services.server.AbstractSkeleton;
import com.google.common.base.Preconditions;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.hessian.HessianFunction;

/**
 * Provides the metadata of a Hessian service implementation.
 */
public final class HessianServiceMetadata {

    private final Class<?> apiInterface;

    /**
     * A map whose key is a method name and whose value is HessianFunction.
     */
    private final Map<String, HessianFunction> functions = new HashMap<>();

    private final boolean blocking;

    /**
     * Creates a new instance from a single Hessian service interface.
     */
    public HessianServiceMetadata(Class<?> serviceType, Object implementation) {
        this(serviceType, implementation, true);
    }

    /**
     * Creates a new instance from a single Hessian service interface.
     */
    public HessianServiceMetadata(Class<?> serviceType, Object implementation, boolean blocking) {
        requireNonNull(serviceType, "serviceType");
        requireNonNull(implementation, "implementation");
        Preconditions.checkArgument(serviceType.isInstance(implementation),
                                    implementation.getClass() + " not instance of " + serviceType.getName());
        apiInterface = serviceType;
        this.blocking = blocking;
        init(implementation, serviceType);
    }

    private void init(Object implementation, Class<?> serviceTypes) {

        // Build the map of method names and their corresponding process functions.
        // If a method is defined multiple times, we take the first definition

        final Method[] methodList = serviceTypes.getMethods();

        for (Method method : methodList) {
            final HessianFunction function = HessianFunction.of(serviceTypes, method, method.getName(),
                                                                implementation,
                                                                blocking);

            functions.putIfAbsent(method.getName(), function);

            final Class<?>[] param = method.getParameterTypes();
            final String mangledName = method.getName() + "__" + param.length;
            functions.put(mangledName, function);
            functions.put(mangleName0(method), function);
        }

        if (functions.isEmpty()) {
            throw new IllegalArgumentException(
                    '\'' + implementation.getClass().getName() + "' is not a Hessian service implementation.");
        }
    }

    private static String mangleName0(Method method) {

        final Class<?>[] param = method.getParameterTypes();

        if (param.length == 0) {
            return method.getName();
        } else {
            return AbstractSkeleton.mangleName(method, false);
        }
    }

    /**
     * Returns the Hessian service interfaces implemented.
     */
    public Class<?> interfaces() {
        return apiInterface;
    }

    @Nullable
    public HessianFunction function(String method) {
        return functions.get(method);
    }
}
