/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal.common.thrift;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TBaseAsyncProcessor;
import org.apache.thrift.TBaseProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.Types;

/**
 * Provides the metadata of a Thrift service interface or implementation.
 */
public final class ThriftServiceMetadata {

    private static final Logger logger = LoggerFactory.getLogger(ThriftServiceMetadata.class);

    private final Set<Class<?>> interfaces;

    /**
     * A map whose key is a method name and whose value is {@link AsyncProcessFunction} or
     * {@link ProcessFunction}.
     */
    private final Map<String, ThriftFunction> functions = new HashMap<>();

    /**
     * Creates a new instance from a Thrift service implementation that implements one or more Thrift service
     * interfaces.
     */
    public ThriftServiceMetadata(Object implementation) {
        this(ImmutableList.of(requireNonNull(implementation, "implementation")));
    }

    /**
     * Creates a new instance from a list of Thrift service implementations, while each service can implement
     * one or more Thrift service interfaces.
     */
    public ThriftServiceMetadata(Iterable<?> implementations) {
        requireNonNull(implementations, "implementations");

        final ImmutableSet.Builder<Class<?>> interfaceBuilder = ImmutableSet.builder();
        implementations.forEach(implementation -> {
            requireNonNull(implementation, "implementations contains null.");
            interfaceBuilder.addAll(init(implementation));
        });
        interfaces = interfaceBuilder.build();
    }

    /**
     * Creates a new instance from a single Thrift service interface.
     */
    public ThriftServiceMetadata(Class<?> serviceType) {
        requireNonNull(serviceType, "serviceType");
        interfaces = init(null, Collections.singleton(serviceType));
    }

    private Set<Class<?>> init(Object implementation) {
        return init(implementation, Types.getAllInterfaces(implementation.getClass()));
    }

    private Set<Class<?>> init(@Nullable Object implementation, Iterable<Class<?>> candidateInterfaces) {

        // Build the map of method names and their corresponding process functions.
        // If a method is defined multiple times, we take the first definition
        final Set<Class<?>> interfaces = new HashSet<>();

        for (Class<?> iface : candidateInterfaces) {

            @Nullable
            final Map<String, AsyncProcessFunction<?, ?, ?>> asyncProcessMap;
            asyncProcessMap = getThriftAsyncProcessMap(implementation, iface);
            if (asyncProcessMap != null) {
                final Map<String, String> camelNameMap = getCamelNameMap(asyncProcessMap.keySet(),
                                                                         iface.getMethods());
                asyncProcessMap.forEach(
                        (name, func) -> registerFunction(iface, name, camelNameMap.get(name),
                                                         func, implementation));
                interfaces.add(iface);
            }

            @Nullable
            final Map<String, ProcessFunction<?, ?>> processMap;
            processMap = getThriftProcessMap(implementation, iface);
            if (processMap != null) {
                final Map<String, String> camelNameMap = getCamelNameMap(processMap.keySet(),
                                                                         iface.getMethods());
                processMap.forEach(
                        (name, func) -> registerFunction(iface, name, camelNameMap.get(name), func,
                                                         implementation));
                interfaces.add(iface);
            }
        }

        if (functions.isEmpty()) {
            if (implementation != null) {
                throw new IllegalArgumentException('\'' + implementation.getClass().getName() +
                                                   "' is not a Thrift service implementation.");
            } else {
                throw new IllegalArgumentException("not a Thrift service interface: " + candidateInterfaces);
            }
        }

        return Collections.unmodifiableSet(interfaces);
    }

    @Nullable
    private static Map<String, ProcessFunction<?, ?>> getThriftProcessMap(@Nullable Object service,
                                                                          Class<?> iface) {
        final String name = iface.getName();
        if (!name.endsWith("$Iface")) {
            return null;
        }

        final String processorName = name.substring(0, name.length() - 5) + "Processor";
        try {
            final Class<?> processorClass = Class.forName(processorName, false, iface.getClassLoader());
            if (!TBaseProcessor.class.isAssignableFrom(processorClass)) {
                return null;
            }

            final Constructor<?> processorConstructor = processorClass.getConstructor(iface);

            @SuppressWarnings("rawtypes")
            final TBaseProcessor processor = (TBaseProcessor) processorConstructor.newInstance(service);

            @SuppressWarnings("unchecked")
            final Map<String, ProcessFunction<?, ?>> processMap =
                    (Map<String, ProcessFunction<?, ?>>) processor.getProcessMapView();

            return processMap;
        } catch (Exception e) {
            logger.debug("Failed to retrieve the process map from: {}", iface, e);
            return null;
        }
    }

    @Nullable
    private static Map<String, AsyncProcessFunction<?, ?, ?>> getThriftAsyncProcessMap(
            @Nullable Object service, Class<?> iface) {

        final String name = iface.getName();
        if (!name.endsWith("$AsyncIface")) {
            return null;
        }

        final String processorName = name.substring(0, name.length() - 10) + "AsyncProcessor";
        try {
            final Class<?> processorClass = Class.forName(processorName, false, iface.getClassLoader());
            if (!TBaseAsyncProcessor.class.isAssignableFrom(processorClass)) {
                return null;
            }

            final Constructor<?> processorConstructor = processorClass.getConstructor(iface);

            @SuppressWarnings("rawtypes")
            final TBaseAsyncProcessor processor =
                    (TBaseAsyncProcessor) processorConstructor.newInstance(service);

            @SuppressWarnings("unchecked")
            final Map<String, AsyncProcessFunction<?, ?, ?>> processMap =
                    (Map<String, AsyncProcessFunction<?, ?, ?>>) processor.getProcessMapView();

            return processMap;
        } catch (Exception e) {
            logger.debug("Failed to retrieve the asynchronous process map from:: {}", iface, e);
            return null;
        }
    }

    private static Map<String, String> getCamelNameMap(Set<String> processorNames, Method[] methods) {
        final Map<String, String> camelNameMap = new HashMap<>(processorNames.size());
        final Map<String, String> processorNameMap = new HashMap<>(processorNames.size());

        // generate lowCamel name map
        for (String name : processorNames) {
            processorNameMap.put(ThriftFunction.getCamelMethodName(name), name);
        }

        // check lowCamel name
        for (Method method : methods) {
            final String name = method.getName();
            if (!processorNames.contains(name)) {
                camelNameMap.put(processorNameMap.get(name), name);
            }
        }
        return camelNameMap;
    }

    @SuppressWarnings("rawtypes")
    private void registerFunction(Class<?> iface, String name, @Nullable String camelName,
                                  Object func, @Nullable Object implementation) {
        if (functions.containsKey(name)) {
            logger.warn("duplicate Thrift method name: " + name);
            return;
        }

        try {
            final ThriftFunction f;
            if (func instanceof ProcessFunction) {
                f = new ThriftFunction(iface, (ProcessFunction) func, implementation);
            } else {
                f = new ThriftFunction(iface, (AsyncProcessFunction) func, implementation);
            }
            functions.put(name, f);
            if (camelName != null) {
                functions.put(camelName, f);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to retrieve function metadata: " +
                                               iface.getName() + '.' + name + "()", e);
        }
    }

    /**
     * Returns the Thrift service interfaces implemented.
     */
    public Set<Class<?>> interfaces() {
        return interfaces;
    }

    /**
     * Returns the {@link ThriftFunction} that provides the metadata of the specified Thrift function.
     *
     * @return the {@link ThriftFunction}, or {@code null} if there's no such function.
     */
    @Nullable
    public ThriftFunction function(String method) {
        return functions.get(method);
    }
}
