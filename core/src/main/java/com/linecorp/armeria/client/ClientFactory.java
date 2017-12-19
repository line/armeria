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

package com.linecorp.armeria.client;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * Creates and manages clients.
 *
 * <h3>Life cycle of the default {@link ClientFactory}</h3>
 * <p>
 * {@link Clients} or {@link ClientBuilder} uses {@link #DEFAULT}, the default {@link ClientFactory},
 * unless you specified a {@link ClientFactory} explicitly. Calling {@link #close()} on the default
 * {@link ClientFactory} will neither terminate its I/O threads nor release other related resources unlike
 * other {@link ClientFactory} to protect itself from accidental premature termination.
 * </p><p>
 * Instead, when the current {@link ClassLoader} is {@linkplain ClassLoader#getSystemClassLoader() the system
 * class loader}, a {@link Runtime#addShutdownHook(Thread) shutdown hook} is registered so that they are
 * released when the JVM exits.
 * </p><p>
 * If you are in an environment managed by a container or you desire the early termination of the default
 * {@link ClientFactory}, use {@link #closeDefault()}.
 * </p>
 */
public interface ClientFactory extends AutoCloseable {

    /**
     * The default {@link ClientFactory} implementation.
     */
    ClientFactory DEFAULT = new ClientFactoryBuilder().build();

    /**
     * Closes the default {@link ClientFactory}.
     */
    static void closeDefault() {
        LoggerFactory.getLogger(ClientFactory.class).debug(
                "Closing the default {}", ClientFactory.class.getSimpleName());
        ((DefaultClientFactory) DEFAULT).doClose();
    }

    /**
     * Returns the {@link Scheme}s supported by this {@link ClientFactory}.
     */
    Set<Scheme> supportedSchemes();

    /**
     * Returns the {@link EventLoopGroup} being used by this {@link ClientFactory}. Can be used to, e.g.,
     * schedule a periodic task without creating a separate event loop. Use {@link #eventLoopSupplier()}
     * instead if what you need is an {@link EventLoop} rather than an {@link EventLoopGroup}.
     */
    EventLoopGroup eventLoopGroup();

    /**
     * Returns a {@link Supplier} that provides one of the {@link EventLoop}s being used by this
     * {@link ClientFactory}.
     */
    Supplier<EventLoop> eventLoopSupplier();

    /**
     * Acquires an {@link EventLoop} that is expected to handle a connection to the specified {@link Endpoint}.
     * The caller must release the returned {@link EventLoop} back by calling {@link ReleasableHolder#release()}
     * so that {@link ClientFactory} utilizes {@link EventLoop}s efficiently.
     */
    ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint);

    /**
     * Returns the {@link MeterRegistry} that collects various stats.
     */
    MeterRegistry meterRegistry();

    /**
     * Sets the {@link MeterRegistry} that collects various stats. Note that this method is intended to be
     * used during the initialization phase of an application, so that the application gets a chance to
     * switch to the preferred {@link MeterRegistry} implementation. Invoking this method after this factory
     * started to export stats to the old {@link MeterRegistry} may result in undocumented behavior.
     */
    void setMeterRegistry(MeterRegistry meterRegistry);

    /**
     * Creates a new client that connects to the specified {@code uri}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(String uri, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@code uri}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(String uri, Class<T> clientType, ClientOptions options);

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(URI uri, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(URI uri, Class<T> clientType, ClientOptions options);

    /**
     * Returns the {@link ClientBuilderParams} held in {@code client}. This is used when creating a new derived
     * {@link Client} which inherits {@link ClientBuilderParams} from {@code client}. If this
     * {@link ClientFactory} does not know how to handle the {@link ClientBuilderParams} for the provided
     * {@code client}, it should return {@link Optional#empty()}.
     */
    <T> Optional<ClientBuilderParams> clientBuilderParams(T client);

    /**
     * Closes all clients managed by this factory and shuts down the {@link EventLoopGroup}
     * created implicitly by this factory.
     */
    @Override
    void close();
}
