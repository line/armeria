/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;

/**
 * A dual-key registry for {@link XdsExtensionFactory} instances.
 * Factories are resolved by type URL (primary) or extension name (fallback).
 *
 * <p>Also serves as the single entry point for {@link Any}-related operations:
 * factory lookup ({@link #query}) and proto decode ({@link #unpack}).
 */
final class XdsExtensionRegistry {

    private final Map<String, XdsExtensionFactory> byTypeUrl;
    private final Map<String, XdsExtensionFactory> byName;
    private final XdsResourceValidator validator;

    private XdsExtensionRegistry(Map<String, XdsExtensionFactory> byTypeUrl,
                                 Map<String, XdsExtensionFactory> byName,
                                 XdsResourceValidator validator) {
        this.byTypeUrl = byTypeUrl;
        this.byName = byName;
        this.validator = validator;
    }

    @VisibleForTesting
    static XdsExtensionRegistry of(XdsResourceValidator validator) {
        final Map<String, XdsExtensionFactory> byName = new HashMap<>();
        final Map<String, XdsExtensionFactory> byTypeUrl = new HashMap<>();

        // Load SPI-discovered HttpFilterFactory instances as base factories
        ServiceLoader.load(HttpFilterFactory.class).forEach(factory -> {
            register(factory, byName, byTypeUrl);
        });

        // Built-in network filter factories
        register(HttpConnectionManagerFactory.INSTANCE, byName, byTypeUrl);

        // Built-in transport socket factories
        register(UpstreamTlsTransportSocketFactory.INSTANCE, byName, byTypeUrl);
        register(RawBufferTransportSocketFactory.INSTANCE, byName, byTypeUrl);

        return new XdsExtensionRegistry(ImmutableMap.copyOf(byTypeUrl),
                                        ImmutableMap.copyOf(byName), validator);
    }

    private static void register(XdsExtensionFactory factory,
                                 Map<String, XdsExtensionFactory> byName,
                                 Map<String, XdsExtensionFactory> byTypeUrl) {
        byName.put(factory.name(), factory);
        for (String typeUrl : factory.typeUrls()) {
            byTypeUrl.put(typeUrl, factory);
        }
    }

    XdsResourceValidator validator() {
        return validator;
    }

    /**
     * Validates the given message using both pgv structural validation and supported-field
     * validation.
     */
    void assertValid(Object message) {
        validator.assertValid(message);
    }

    /**
     * Unpacks an {@link Any} into the expected proto type using the validator.
     */
    <T extends Message> T unpack(Any any, Class<T> expectedType) {
        return validator.unpack(any, expectedType);
    }

    /**
     * Looks up a factory by typeUrl and validates it implements the expected type.
     * Returns {@code null} if no factory is registered.
     *
     * @throws IllegalArgumentException if the factory does not implement the expected interface
     */
    @Nullable
    @VisibleForTesting
    <T extends XdsExtensionFactory> T queryByTypeUrl(String typeUrl, Class<T> expectedType) {
        final XdsExtensionFactory factory = byTypeUrl.get(typeUrl);
        if (factory == null) {
            return null;
        }
        if (!expectedType.isInstance(factory)) {
            throw new IllegalArgumentException(
                    "Factory for typeUrl '" + typeUrl + "' is " + factory.getClass().getName() +
                    ", expected " + expectedType.getName());
        }
        return expectedType.cast(factory);
    }

    /**
     * Looks up a factory by name and validates it implements the expected type.
     * Returns {@code null} if no factory is registered.
     *
     * @throws IllegalArgumentException if the factory does not implement the expected interface
     */
    @Nullable
    <T extends XdsExtensionFactory> T queryByName(String name, Class<T> expectedType) {
        final XdsExtensionFactory factory = byName.get(name);
        if (factory == null) {
            return null;
        }
        if (!expectedType.isInstance(factory)) {
            throw new IllegalArgumentException(
                    "Factory for name '" + name + "' is " + factory.getClass().getName() +
                    ", expected " + expectedType.getName());
        }
        return expectedType.cast(factory);
    }

    /**
     * Resolves a factory by {@link Any}'s typeUrl first, then by name.
     * Returns {@code null} if no factory is found.
     *
     * @throws IllegalArgumentException if a found factory does not implement the expected interface
     */
    @Nullable
    <T extends XdsExtensionFactory> T query(Any any, @Nullable String name, Class<T> expectedType) {
        final T factory = queryByTypeUrl(any.getTypeUrl(), expectedType);
        if (factory != null) {
            return factory;
        }
        if (name != null) {
            return queryByName(name, expectedType);
        }
        return null;
    }
}
