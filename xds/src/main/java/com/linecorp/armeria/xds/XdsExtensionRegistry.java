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

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.client.endpoint.ClusterTypeFactory;
import com.linecorp.armeria.xds.client.endpoint.RouterFilterFactory;
import com.linecorp.armeria.xds.client.endpoint.StaticClusterTypeFactory;
import com.linecorp.armeria.xds.client.endpoint.StrictDnsClusterTypeFactory;
import com.linecorp.armeria.xds.configsource.SotwConfigSourceSubscriptionFactory;
import com.linecorp.armeria.xds.filter.CredentialInjectorFilterFactory;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A dual-key registry for {@link XdsExtensionFactory} instances.
 * Factories are resolved by type URL (primary) or extension name (fallback).
 *
 * <p>Also serves as the single entry point for {@link Any}-related operations:
 * factory lookup ({@link #query}) and proto decode ({@link #unpack}).
 */
@UnstableApi
public final class XdsExtensionRegistry {

    private static final Set<Class<? extends XdsExtensionFactory>> SUPPORTED_FACTORY_TYPES =
            ImmutableSet.of(HttpFilterFactory.class,
                            SotwConfigSourceSubscriptionFactory.class,
                            ClusterTypeFactory.class);

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

    static XdsExtensionRegistry of(XdsResourceValidator validator,
                                   DirectoryWatchService watchService,
                                   MeterRegistry meterRegistry,
                                   MeterIdPrefix meterIdPrefix,
                                   List<XdsExtensionFactory> extensionFactories) {
        final ImmutableMap.Builder<String, XdsExtensionFactory> byName = ImmutableMap.builder();
        final ImmutableMap.Builder<String, XdsExtensionFactory> byTypeUrl = ImmutableMap.builder();

        // SPI-loaded factories (user-provided extensions)
        for (XdsExtensionFactoryProvider provider : ServiceLoader.load(XdsExtensionFactoryProvider.class)) {
            final XdsExtensionFactory factory = provider.newFactory();
            validateFactoryType(factory);
            register(factory, byName, byTypeUrl);
        }

        // Builder-provided factories
        for (XdsExtensionFactory factory : extensionFactories) {
            validateFactoryType(factory);
            register(factory, byName, byTypeUrl);
        }

        // Built-in factories (registered last so they cannot be overridden)
        register(new RouterFilterFactory(), byName, byTypeUrl);
        register(new CredentialInjectorFilterFactory(), byName, byTypeUrl);
        register(new StaticClusterTypeFactory(), byName, byTypeUrl);
        register(new StrictDnsClusterTypeFactory(), byName, byTypeUrl);
        register(new PathSotwConfigSourceSubscriptionFactory(watchService, meterRegistry, meterIdPrefix),
                 byName, byTypeUrl);
        register(new GrpcConfigSourceStreamFactory(meterRegistry, meterIdPrefix), byName, byTypeUrl);
        register(new EdsClusterTypeFactory(), byName, byTypeUrl);
        register(HttpConnectionManagerFactory.INSTANCE, byName, byTypeUrl);
        register(UpstreamTlsTransportSocketFactory.INSTANCE, byName, byTypeUrl);
        register(DownstreamTlsTransportSocketFactory.INSTANCE, byName, byTypeUrl);
        register(RawBufferTransportSocketFactory.INSTANCE, byName, byTypeUrl);

        return new XdsExtensionRegistry(byTypeUrl.buildKeepingLast(), byName.buildKeepingLast(), validator);
    }

    private static void validateFactoryType(XdsExtensionFactory factory) {
        for (Class<? extends XdsExtensionFactory> type : SUPPORTED_FACTORY_TYPES) {
            if (type.isInstance(factory)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported factory type: " + factory.getClass().getName() +
                ". Must implement one of: " + SUPPORTED_FACTORY_TYPES);
    }

    private static void register(XdsExtensionFactory factory,
                                 ImmutableMap.Builder<String, XdsExtensionFactory> byName,
                                 ImmutableMap.Builder<String, XdsExtensionFactory> byTypeUrl) {
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
    void assertValid(Message message) {
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
    public <T extends XdsExtensionFactory> T queryByName(String name, Class<T> expectedType) {
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
    public <T extends XdsExtensionFactory> T query(Any any, String name, Class<T> expectedType) {
        final T factory = queryByTypeUrl(any.getTypeUrl(), expectedType);
        if (factory != null) {
            return factory;
        }
        if (!name.isEmpty()) {
            return queryByName(name, expectedType);
        }
        return null;
    }
}
