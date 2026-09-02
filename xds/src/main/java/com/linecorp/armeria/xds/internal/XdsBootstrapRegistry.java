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

import java.util.Collections;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.ShutdownHooks;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsBootstrapProvider;

public final class XdsBootstrapRegistry {

    private static final Logger logger = LoggerFactory.getLogger(XdsBootstrapRegistry.class);

    public static final String DEFAULT_NAME = "default";

    private static final ConcurrentHashMap<String, Supplier<XdsBootstrap>> registry =
            new ConcurrentHashMap<>();

    private static final Set<String> spiNames;

    static {
        final Set<String> loaded = new HashSet<>();
        for (XdsBootstrapProvider provider : ServiceLoader.load(
                XdsBootstrapProvider.class, XdsBootstrapRegistry.class.getClassLoader())) {
            final String name = provider.name();
            final Supplier<XdsBootstrap> existing = registry.putIfAbsent(name, Suppliers.memoize(() -> {
                logger.debug("Creating XdsBootstrap '{}' from {}", name,
                             provider.getClass().getName());
                final XdsBootstrap bootstrap = provider.newBootstrap();
                ShutdownHooks.addClosingTask(bootstrap);
                return bootstrap;
            }));
            if (existing != null) {
                logger.warn("Duplicate XdsBootstrapProvider for name '{}'; ignoring {}",
                            name, provider.getClass().getName());
            } else {
                loaded.add(name);
            }
        }
        spiNames = Collections.unmodifiableSet(loaded);
    }

    @Nullable
    public static XdsBootstrap find(String name) {
        final Supplier<XdsBootstrap> supplier = registry.get(requireNonNull(name, "name"));
        return supplier != null ? supplier.get() : null;
    }

    @VisibleForTesting
    public static void register(String name, XdsBootstrap bootstrap) {
        requireNonNull(name, "name");
        requireNonNull(bootstrap, "bootstrap");
        final Supplier<XdsBootstrap> existing = registry.putIfAbsent(name, () -> bootstrap);
        checkState(existing == null,
                   "An XdsBootstrap is already registered with name '%s'", name);
    }

    @VisibleForTesting
    @Nullable
    public static XdsBootstrap deregister(String name) {
        requireNonNull(name, "name");
        checkArgument(!spiNames.contains(name),
                      "Cannot deregister SPI-loaded bootstrap '%s'", name);
        final Supplier<XdsBootstrap> supplier = registry.remove(name);
        return supplier != null ? supplier.get() : null;
    }

    private XdsBootstrapRegistry() {}
}
