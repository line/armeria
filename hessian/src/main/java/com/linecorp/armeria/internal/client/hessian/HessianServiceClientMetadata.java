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
package com.linecorp.armeria.internal.client.hessian;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caucho.services.server.AbstractSkeleton;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.hessian.HessianFunction;

/**
 * Provides the metadata of a Hessian service interface.
 */
public final class HessianServiceClientMetadata {

    private final boolean isOverloadEnabled;

    private static final Logger logger = LoggerFactory.getLogger(HessianServiceClientMetadata.class);

    private final Set<Class<?>> interfaces;

    /**
     * A map whose key is a method name and whose value is HessianFunction.
     */
    private final Map<String, HessianFunction> functions = new HashMap<>();

    private final ConcurrentMap<Method, String> mangleMap = new ConcurrentHashMap<>();

    /**
     * Creates a new instance from a single Hessian service interface.
     */
    public HessianServiceClientMetadata(Class<?> serviceType) {
        this(serviceType, false);
    }

    /**
     * Creates a new instance from a single Hessian service interface.
     */
    public HessianServiceClientMetadata(Class<?> serviceType, boolean isOverloadEnabled) {
        requireNonNull(serviceType, "serviceType");
        this.isOverloadEnabled = isOverloadEnabled;
        interfaces = init(Collections.singleton(serviceType));
    }

    private Set<Class<?>> init(Iterable<Class<?>> candidateInterfaces) {

        // Build the map of method names and their corresponding process functions.
        // If a method is defined multiple times, we take the first definition
        final Set<Class<?>> interfaces = new HashSet<>();

        for (Class<?> serviceTypes : candidateInterfaces) {
            for (Method method : serviceTypes.getMethods()) {
                final String name = methodName(method);
                if (functions.containsKey(name)) {
                    logger.warn("duplicate Hessian method name: {}", name);
                    continue;
                }
                final HessianFunction
                        function = HessianFunction.of(serviceTypes, method, name, null);
                functions.put(name, function);
                mangleMap.put(method, name);
            }
            interfaces.add(serviceTypes);
        }

        if (functions.isEmpty()) {
            throw new IllegalArgumentException("not a Hessian service interface: " + candidateInterfaces);
        }

        return Collections.unmodifiableSet(interfaces);
    }

    private String methodName(Method method) {
        return !isOverloadEnabled ? method.getName() : genMangleName(method);
    }

    private static String genMangleName(Method method) {

        final Class<?>[] param = method.getParameterTypes();

        if (param == null || param.length == 0) {
            return method.getName();
        } else {
            return AbstractSkeleton.mangleName(method, false);
        }
    }

    /**
     * Returns the Hessian service interfaces implemented.
     */
    public Set<Class<?>> interfaces() {
        return interfaces;
    }

    @Nullable
    public HessianFunction function(String method) {
        return functions.get(method);
    }

    @Nullable
    public String mangleName(Method method) {
        return mangleMap.get(method);
    }
}
