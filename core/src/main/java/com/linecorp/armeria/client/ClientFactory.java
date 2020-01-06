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

import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.Unwrappable;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * Creates and manages clients.
 *
 * <h3>Life cycle of the default {@link ClientFactory}</h3>
 * <p>
 * {@link Clients} or {@link ClientBuilder} uses the default {@link ClientFactory} returned by
 * {@link #ofDefault()}, unless you specified a {@link ClientFactory} explicitly. Calling {@link #close()}
 * on the default {@link ClientFactory} will neither terminate its I/O threads nor release other related
 * resources unlike other {@link ClientFactory} to protect itself from accidental premature termination.
 * </p><p>
 * Instead, when the current {@link ClassLoader} is {@linkplain ClassLoader#getSystemClassLoader() the system
 * class loader}, a {@linkplain Runtime#addShutdownHook(Thread) shutdown hook} is registered so that they are
 * released when the JVM exits.
 * </p><p>
 * If you are in a multi-classloader environment or you desire an early/explicit termination of the default
 * {@link ClientFactory}, use {@link #closeDefault()}.
 * </p>
 */
public interface ClientFactory extends AutoCloseable {

    /**
     * The default {@link ClientFactory} implementation.
     *
     * @deprecated Use {@link #ofDefault()}.
     */
    @Deprecated
    ClientFactory DEFAULT = DefaultClientFactory.DEFAULT;

    /**
     * Returns the default {@link ClientFactory} implementation.
     */
    static ClientFactory ofDefault() {
        return DefaultClientFactory.DEFAULT;
    }

    /**
     * Returns the insecure default {@link ClientFactory} implementation which does not verify server's TLS
     * certificate chain.
     */
    static ClientFactory insecure() {
        return DefaultClientFactory.INSECURE;
    }

    /**
     * Returns a newly created {@link ClientFactoryBuilder}.
     */
    static ClientFactoryBuilder builder() {
        return new ClientFactoryBuilder();
    }

    /**
     * Closes the default {@link ClientFactory}.
     */
    static void closeDefault() {
        LoggerFactory.getLogger(ClientFactory.class).debug(
                "Closing the default {}", ClientFactory.class.getSimpleName());
        try {
            DefaultClientFactory.DEFAULT.doClose();
        } finally {
            DefaultClientFactory.INSECURE.doClose();
        }
    }

    /**
     * Disables the {@linkplain Runtime#addShutdownHook(Thread) shutdown hook} which closes
     * {@linkplain #ofDefault() the default <code>ClientFactory</code>}. This method is useful when you need
     * full control over the life cycle of the default {@link ClientFactory}.
     */
    static void disableShutdownHook() {
        DefaultClientFactory.disableShutdownHook0();
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
    ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint, SessionProtocol sessionProtocol);

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
     * Returns the {@link ClientFactoryOptions} that has been used to create this {@link ClientFactory}.
     */
    ClientFactoryOptions options();

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
     * Creates a new client that connects to the specified {@link URI}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(URI uri, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link URI}.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(URI uri, Class<T> clientType, ClientOptions options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType, ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, Class<T> clientType, ClientOptions options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}
     * and {@code path}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param path the service {@code path}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                    ClientOptionValue<?>... options);

    /**
     * Creates a new client that connects to the specified {@link Endpoint} with the {@link Scheme}
     * and {@code path}.
     *
     * @param scheme the {@link Scheme} for the {@code endpoint}
     * @param endpoint the server {@link Endpoint}
     * @param path the service {@code path}
     * @param clientType the type of the new client
     * @param options the {@link ClientOptions}
     */
    <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                    ClientOptions options);

    /**
     * Returns the {@link ClientBuilderParams} held in {@code client}. This is used when creating a new derived
     * {@link Client} which inherits {@link ClientBuilderParams} from {@code client}. If this
     * {@link ClientFactory} does not know how to handle the {@link ClientBuilderParams} for the provided
     * {@code client}, it should return {@code null}.
     */
    @Nullable
    default <T> ClientBuilderParams clientBuilderParams(T client) {
        return unwrap(client, ClientBuilderParams.class);
    }

    /**
     * Unwraps the specified {@code client} object into the object of the specified {@code type}. For example,
     * <pre>{@code
     * ClientFactory clientFactory = ...;
     * WebClient client = WebClient.builder(...)
     *                             .factory(clientFactory)
     *                             .decorator(LoggingClient.newDecorator())
     *                             .build();
     *
     * LoggingClient unwrapped = clientFactory.unwrap(client, LoggingClient.class);
     *
     * // If the client implements Unwrappable, you can just use the 'as()' method.
     * LoggingClient unwrapped2 = client.as(LoggingClient.class);
     * }</pre>
     *
     * @param client the client object
     * @param type the type of the object to return
     * @return the object of the specified {@code type} if found, or {@code null} if not found.
     *
     * @see Client#as(Class)
     * @see Clients#unwrap(Object, Class)
     * @see Unwrappable
     */
    @Nullable
    default <T> T unwrap(Object client, Class<T> type) {
        requireNonNull(client, "client");
        requireNonNull(type, "type");

        if (type.isInstance(client)) {
            return type.cast(client);
        }

        if (client instanceof Unwrappable) {
            return ((Unwrappable) client).as(type);
        }

        if (Proxy.isProxyClass(client.getClass())) {
            final InvocationHandler handler = Proxy.getInvocationHandler(client);
            if (type.isInstance(handler)) {
                return type.cast(handler);
            }

            if (handler instanceof Unwrappable) {
                return ((Unwrappable) handler).as(type);
            }
        }

        return null;
    }

    /**
     * Closes all clients managed by this factory and shuts down the {@link EventLoopGroup}
     * created implicitly by this factory.
     */
    @Override
    void close();
}
