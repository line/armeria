/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Scheme;

/**
 * A {@link ClientFactory} which combines all discovered {@link ClientFactory} implementations.
 *
 * <h3>How are the {@link ClientFactory}s discovered?</h3>
 *
 * <p>{@link AllInOneClientFactory} looks up the {@link ClientFactoryProvider}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link ClientFactoryProvider} implementations will create
 * the {@link ClientFactory} implementations.
 */
public class AllInOneClientFactory extends NonDecoratingClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(AllInOneClientFactory.class);

    static {
        if (AllInOneClientFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            Runtime.getRuntime().addShutdownHook(new Thread(ClientFactory::closeDefault));
        }
    }

    private final Map<Scheme, ClientFactory> clientFactories;

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     */
    public AllInOneClientFactory() {
        this(false);
    }

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    public AllInOneClientFactory(boolean useDaemonThreads) {
        this(SessionOptions.DEFAULT, useDaemonThreads);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     */
    public AllInOneClientFactory(SessionOptions options) {
        this(options, false);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    public AllInOneClientFactory(SessionOptions options, boolean useDaemonThreads) {
        super(options, useDaemonThreads);

        // TODO(trustin): Allow specifying different options for different session protocols.
        //                We have only one session protocol at the moment, so this is OK so far.

        // Do not let the delegates create or manage event loops by specifying the EventLoop explicitly.
        // See NonDecoratingClientFactory.closeEventLoopGroup for more information.
        final SessionOptions delegateOptions =
                SessionOptions.of(options, SessionOption.EVENT_LOOP_GROUP.newValue(eventLoopGroup()));

        final List<ClientFactoryProvider> providers =
                Streams.stream(ServiceLoader.load(ClientFactoryProvider.class,
                                                  AllInOneClientFactory.class.getClassLoader()))
                       .collect(Collectors.toCollection(ArrayList::new));

        if (providers.isEmpty()) {
            throw new IllegalStateException("could not find any providers");
        }

        final List<ClientFactory> availableClientFactories = new ArrayList<>();
        do {
            boolean added = false;
            for (Iterator<ClientFactoryProvider> i = providers.iterator(); i.hasNext();) {
                final ClientFactoryProvider p = i.next();

                // Find the ClientFactory instances who meet the type requirements specified in
                // ClientFactoryProvider.dependencies().
                final Map<Class<?>, ClientFactory> requiredClientFactories =
                        findRequiredClientFactories(p, availableClientFactories);

                if (requiredClientFactories == null) {
                    // Dependencies are not met yet. Try instantiating others first.
                    continue;
                }

                // Dependencies are met.
                // Remove the provider from the list so that we instantiate a ClientFactory only once.
                i.remove();

                // Instantiate a ClientFactory and add it to the list of available factories as well.
                availableClientFactories.add(p.newFactory(delegateOptions, requiredClientFactories));
                added = true;
            }

            // If no factories were instantiated, it means none of the remaining providers could find
            // its dependencies.
            if (!added) {
                throw new IllegalStateException("failed to find the dependencies for: " + providers);
            }
        } while (!providers.isEmpty());

        final ImmutableMap.Builder<Scheme, ClientFactory> builder = ImmutableMap.builder();
        for (ClientFactory f : availableClientFactories) {
            f.supportedSchemes().forEach(s -> builder.put(s, f));
        }

        clientFactories = builder.build();
    }

    /**
     * Builds the dependency map for the specified {@link ClientFactoryProvider}.
     *
     * @return {@code null} if dependencies could not be fully met
     */
    @Nullable
    private static Map<Class<?>, ClientFactory> findRequiredClientFactories(
            ClientFactoryProvider provider, Iterable<ClientFactory> availableClientFactories) {

        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Set<Class<ClientFactory>> dependencyTypes =
                (Set<Class<ClientFactory>>) (Set) provider.dependencies();

        if (dependencyTypes.isEmpty()) {
            return ImmutableMap.of();
        }

        final ImmutableMap.Builder<Class<?>, ClientFactory> builder = ImmutableMap.builder();
        for (Class<ClientFactory> dependencyType : dependencyTypes) {
            boolean found = false;
            for (ClientFactory f : availableClientFactories) {
                if (dependencyType.isInstance(f)) {
                    builder.put(dependencyType, f);
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Could not find all dependencies.
                return null;
            }
        }

        return builder.build();
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return clientFactories.keySet();
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);
        return clientFactories.get(scheme).newClient(uri, clientType, options);
    }

    @Override
    public void close() {
        // The global default should never be closed.
        if (this == ClientFactory.DEFAULT) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         ClientFactory.class.getSimpleName());
            return;
        }

        doClose();
    }

    void doClose() {
        try {
            clientFactories.values().forEach(ClientFactory::close);
        } finally {
            super.close();
        }
    }
}
