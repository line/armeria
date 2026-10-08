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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import com.google.common.collect.ImmutableList;
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
import com.linecorp.armeria.xds.filter.FaultInjectionFilterFactory;
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

    private final Map<String, XdsExtensionFactory> singleTypeUrl;
    private final Map<String, Map<String, XdsExtensionFactory>> byTypeUrl;
    private final Map<String, XdsExtensionFactory> byName;
    private final XdsResourceValidator validator;

    private XdsExtensionRegistry(Map<String, XdsExtensionFactory> singleTypeUrl,
                                 Map<String, Map<String, XdsExtensionFactory>> byTypeUrl,
                                 Map<String, XdsExtensionFactory> byName,
                                 XdsResourceValidator validator) {
        this.singleTypeUrl = singleTypeUrl;
        this.byTypeUrl = byTypeUrl;
        this.byName = byName;
        this.validator = validator;
    }

    static XdsExtensionRegistry of(XdsResourceValidator validator,
                                   DirectoryWatchService watchService,
                                   MeterRegistry meterRegistry,
                                   MeterIdPrefix meterIdPrefix,
                                   List<XdsExtensionFactory> extensionFactories) {
        final List<XdsExtensionFactory> allFactories = new ArrayList<>();

        // Level 1: SPI-loaded factories
        final List<XdsExtensionFactory> spiFactories = new ArrayList<>();
        for (XdsExtensionFactoryProvider provider : ServiceLoader.load(XdsExtensionFactoryProvider.class)) {
            spiFactories.add(provider.newFactory());
        }
        spiFactories.forEach(XdsExtensionRegistry::validateFactoryType);
        validateDuplicates(spiFactories);
        allFactories.addAll(spiFactories);

        // Level 2: Builder-provided factories
        extensionFactories.forEach(XdsExtensionRegistry::validateFactoryType);
        validateDuplicates(extensionFactories);
        allFactories.addAll(extensionFactories);

        // Level 3: Built-in factories (registered last so they cannot be overridden)
        final List<XdsExtensionFactory> builtInFactories =
                ImmutableList.<XdsExtensionFactory>builder()
                        .add(new RouterFilterFactory())
                        .add(new CredentialInjectorFilterFactory())
                        .add(new FaultInjectionFilterFactory())
                        .add(new StaticClusterTypeFactory())
                        .add(new StrictDnsClusterTypeFactory())
                        .add(new PathSotwConfigSourceSubscriptionFactory(
                                watchService, meterRegistry, meterIdPrefix))
                        .add(new GrpcConfigSourceStreamFactory(meterRegistry, meterIdPrefix))
                        .add(new EdsClusterTypeFactory())
                        .add(HttpConnectionManagerFactory.INSTANCE)
                        .add(UpstreamTlsTransportSocketFactory.INSTANCE)
                        .add(DownstreamTlsTransportSocketFactory.INSTANCE)
                        .add(RawBufferTransportSocketFactory.INSTANCE)
                        .build();
        validateDuplicates(builtInFactories);
        allFactories.addAll(builtInFactories);

        final Map<String, XdsExtensionFactory> byName = new LinkedHashMap<>();
        final Map<String, Map<String, XdsExtensionFactory>> byTypeUrl = new LinkedHashMap<>();
        for (XdsExtensionFactory factory : allFactories) {
            byName.put(factory.name(), factory);
            for (String typeUrl : factory.typeUrls()) {
                byTypeUrl.computeIfAbsent(typeUrl, k -> new LinkedHashMap<>())
                         .put(factory.name(), factory);
            }
        }

        // Derive singleTypeUrl and freeze all maps
        final ImmutableMap.Builder<String, XdsExtensionFactory> singleTypeUrlBuilder =
                ImmutableMap.builder();
        final ImmutableMap.Builder<String, Map<String, XdsExtensionFactory>> frozenByTypeUrl =
                ImmutableMap.builder();
        byTypeUrl.forEach((typeUrl, nameMap) -> {
            final ImmutableMap<String, XdsExtensionFactory> frozen = ImmutableMap.copyOf(nameMap);
            frozenByTypeUrl.put(typeUrl, frozen);
            if (frozen.size() == 1) {
                singleTypeUrlBuilder.put(typeUrl, frozen.values().iterator().next());
            }
        });

        return new XdsExtensionRegistry(singleTypeUrlBuilder.buildOrThrow(),
                                        frozenByTypeUrl.buildOrThrow(),
                                        ImmutableMap.copyOf(byName), validator);
    }

    private static void validateDuplicates(List<? extends XdsExtensionFactory> factories) {
        final Set<String> levelNames = new HashSet<>();
        final Set<String> levelTypeUrlNames = new HashSet<>();
        for (XdsExtensionFactory factory : factories) {
            if (!levelNames.add(factory.name())) {
                throw new IllegalArgumentException(
                        "Duplicate factory name '" + factory.name() +
                        "' within the same registration level");
            }
            for (String typeUrl : factory.typeUrls()) {
                if (!levelTypeUrlNames.add(typeUrl + '\0' + factory.name())) {
                    throw new IllegalArgumentException(
                            "Duplicate (typeUrl, name) '" + typeUrl + "' + '" + factory.name() +
                            "' within the same registration level");
                }
            }
        }
    }

    private static void validateFactoryType(XdsExtensionFactory factory) {
        requireNonNull(factory, "factory");
        for (Class<? extends XdsExtensionFactory> type : SUPPORTED_FACTORY_TYPES) {
            if (type.isInstance(factory)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported factory type: " + factory.getClass().getName() +
                ". Must implement one of: " + SUPPORTED_FACTORY_TYPES);
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
     * If multiple names are registered for the same typeUrl, the name is also considered.
     * Returns {@code null} if no factory is found.
     *
     * @throws IllegalArgumentException if a found factory does not implement the expected interface
     */
    @Nullable
    public <T extends XdsExtensionFactory> T query(Any any, String name, Class<T> expectedType) {
        final String typeUrl = any.getTypeUrl();

        final XdsExtensionFactory unambiguous = singleTypeUrl.get(typeUrl);
        if (unambiguous != null) {
            return castOrThrow(unambiguous, typeUrl, expectedType);
        }

        final Map<String, XdsExtensionFactory> nameMap = byTypeUrl.get(typeUrl);
        if (nameMap != null) {
            final XdsExtensionFactory factory = nameMap.get(name);
            if (factory != null) {
                return castOrThrow(factory, typeUrl, expectedType);
            }
            return null;
        }

        if (!name.isEmpty()) {
            return queryByName(name, expectedType);
        }
        return null;
    }

    private static <T extends XdsExtensionFactory> T castOrThrow(XdsExtensionFactory factory,
                                                                  String typeUrl,
                                                                  Class<T> expectedType) {
        if (!expectedType.isInstance(factory)) {
            throw new IllegalArgumentException(
                    "Factory for typeUrl '" + typeUrl + "' is " + factory.getClass().getName() +
                    ", expected " + expectedType.getName());
        }
        return expectedType.cast(factory);
    }
}
