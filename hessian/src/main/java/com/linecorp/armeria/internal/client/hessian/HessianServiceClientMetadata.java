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
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caucho.services.server.AbstractSkeleton;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.hessian.HessianFunction;

/**
 * Provides the metadata of a Hessian service interface.
 */
public final class HessianServiceClientMetadata {
    private static final Logger logger = LoggerFactory.getLogger(HessianServiceClientMetadata.class);

    private final boolean isOverloadEnabled;
    private final Class<?> apiClass;

    /**
     * A map whose key is a method name and whose value is HessianFunction.
     */
    private final Map<String, HessianFunction> functions = new HashMap<>();

    private final Map<Method, String> mangleMap = new HashMap<>();

    /**
     * Creates a new instance from a single Hessian service interface.
     */
    public HessianServiceClientMetadata(Class<?> apiClass) {
        this(apiClass, false);
    }

    /**
     * Creates a new instance from a single Hessian service interface.
     */
    public HessianServiceClientMetadata(Class<?> apiClass, boolean isOverloadEnabled) {
        requireNonNull(apiClass, "apiClass");
        this.isOverloadEnabled = isOverloadEnabled;
        this.apiClass = apiClass;
        init(apiClass);
    }

    private void init(Class<?> apiClass) {

        // Build the map of method names and their corresponding process functions.
        // If a method is defined multiple times, we take the first definition

        for (Method method : apiClass.getMethods()) {
            final String name = methodName(method);
            if (functions.containsKey(name)) {
                logger.warn("duplicate Hessian method name: {}", name);
                continue;
            }
            final HessianFunction
                    function = HessianFunction.of(apiClass, method, name, null);
            functions.put(name, function);
            mangleMap.put(method, name);
        }

        if (functions.isEmpty()) {
            throw new IllegalArgumentException("not a Hessian service interface: " + apiClass);
        }

    }

    private String methodName(Method method) {
        return !isOverloadEnabled ? method.getName() : genMangleName(method);
    }

    private static String genMangleName(Method method) {
        final Class<?>[] param = method.getParameterTypes();

        if (param.length == 0) {
            return method.getName();
        } else {
            return AbstractSkeleton.mangleName(method, false);
        }
    }

    /**
     * Returns the Hessian service interfaces.
     */
    public Class<?> apiClass() {
        return apiClass;
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
