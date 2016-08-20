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
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;

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
    ClientFactory DEFAULT = new AllInOneClientFactory();

    /**
     * Closes the default {@link ClientFactory}.
     */
    static void closeDefault() {
        LoggerFactory.getLogger(ClientFactory.class).debug(
                "Closing the default {}", ClientFactory.class.getSimpleName());
        ((AllInOneClientFactory) DEFAULT).doClose();
    }

    /**
     * Returns the {@link Scheme}s supported by this {@link ClientFactory}.
     */
    Set<Scheme> supportedSchemes();

    /**
     * Returns the session-layer options of the connections created by this {@link ClientFactory}.
     */
    SessionOptions options();

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
     * Closes all clients managed by this factory and shuts down the {@link EventLoopGroup}
     * created implicitly by this factory.
     */
    @Override
    void close();
}
