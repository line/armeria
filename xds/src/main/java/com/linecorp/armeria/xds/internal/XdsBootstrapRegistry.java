/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.ShutdownHooks;
import com.linecorp.armeria.xds.DefaultXdsBootstrapProvider;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsBootstrapProvider;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.client.endpoint.XdsRpcPreprocessor;

public final class XdsBootstrapRegistry {

    private static final Logger logger = LoggerFactory.getLogger(XdsBootstrapRegistry.class);

    private static final String DEFAULT_BOOTSTRAP_PROPERTY = "com.linecorp.armeria.xds.defaultBootstrap";

    private static final String DEFAULT_NAME =
            System.getProperty(DEFAULT_BOOTSTRAP_PROPERTY, DefaultXdsBootstrapProvider.DEFAULT_NAME);

    public static String defaultName() {
        return DEFAULT_NAME;
    }

    private static final ConcurrentHashMap<String, Supplier<XdsBootstrap>> registry;

    private static final ConcurrentHashMap<String, CachedPreprocessors> preprocessorCache =
            new ConcurrentHashMap<>();

    private static final Set<String> spiNames;

    static {
        final ImmutableMap.Builder<String, Supplier<XdsBootstrap>> spiEntries = ImmutableMap.builder();
        for (XdsBootstrapProvider provider : ServiceLoader.load(
                XdsBootstrapProvider.class, XdsBootstrapRegistry.class.getClassLoader())) {
            final String name = provider.name();
            spiEntries.put(name, Suppliers.memoize(() -> {
                logger.debug("Creating XdsBootstrap '{}' from {}", name, provider.getClass().getName());
                final XdsBootstrap bootstrap = provider.newBootstrap();
                ShutdownHooks.addClosingTask(bootstrap);
                return bootstrap;
            }));
        }
        final Map<String, Supplier<XdsBootstrap>> loaded = spiEntries.buildOrThrow();
        registry = new ConcurrentHashMap<>(loaded);
        spiNames = ImmutableSet.copyOf(loaded.keySet());
    }

    @Nullable
    public static XdsBootstrap find(String name) {
        final Supplier<XdsBootstrap> supplier = registry.get(requireNonNull(name, "name"));
        return supplier != null ? supplier.get() : null;
    }

    public static CachedPreprocessors preprocessors(String bootstrapName, String listenerName) {
        requireNonNull(bootstrapName, "bootstrapName");
        requireNonNull(listenerName, "listenerName");
        final String cacheKey = bootstrapName + '\0' + listenerName;
        return preprocessorCache.computeIfAbsent(cacheKey, k -> {
            final XdsBootstrap bootstrap = find(bootstrapName);
            requireNonNull(bootstrap,
                           "No XdsBootstrap registered with name '" + bootstrapName + "'. " +
                           "Provide an XdsBootstrapProvider via SPI before creating xDS clients.");
            return new CachedPreprocessors(
                    XdsHttpPreprocessor.ofListener(listenerName, bootstrap),
                    XdsRpcPreprocessor.ofListener(listenerName, bootstrap));
        });
    }

    /**
     * Registers a bootstrap for testing. This method is not intended for production use;
     * use {@link XdsBootstrapProvider} SPI instead.
     */
    @VisibleForTesting
    public static synchronized void register(String name, XdsBootstrap bootstrap) {
        requireNonNull(name, "name");
        requireNonNull(bootstrap, "bootstrap");
        final Supplier<XdsBootstrap> existing = registry.putIfAbsent(name, () -> bootstrap);
        checkState(existing == null,
                   "An XdsBootstrap is already registered with name '%s'", name);
    }

    /**
     * Deregisters a bootstrap and its cached preprocessors for testing.
     * SPI-loaded bootstraps cannot be deregistered.
     */
    @VisibleForTesting
    @Nullable
    public static synchronized XdsBootstrap deregister(String name) {
        requireNonNull(name, "name");
        checkArgument(!spiNames.contains(name),
                      "Cannot deregister SPI-loaded bootstrap '%s'", name);
        final Supplier<XdsBootstrap> supplier = registry.remove(name);
        // Remove all cached preprocessors associated with this bootstrap name.
        final String prefix = name + '\0';
        preprocessorCache.keySet().removeIf(s -> s.startsWith(prefix));
        return supplier != null ? supplier.get() : null;
    }

    public static final class CachedPreprocessors {
        private final HttpPreprocessor http;
        private final RpcPreprocessor rpc;

        CachedPreprocessors(HttpPreprocessor http, RpcPreprocessor rpc) {
            this.http = http;
            this.rpc = rpc;
        }

        public HttpPreprocessor http() {
            return http;
        }

        public RpcPreprocessor rpc() {
            return rpc;
        }
    }

    private XdsBootstrapRegistry() {}
}
