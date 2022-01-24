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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.Unwrappable;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import reactor.core.scheduler.NonBlocking;

/**
 * Creates and manages clients.
 *
 * <h2>Life cycle of the default {@link ClientFactory}</h2>
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
public interface ClientFactory extends Unwrappable, ListenableAsyncCloseable {

    Logger logger = LoggerFactory.getLogger(ClientFactory.class);

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
        final Logger logger = LoggerFactory.getLogger(ClientFactory.class);
        logger.debug("Closing the default client factories");
        final CompletableFuture<Void> closeFuture = CompletableFuture.allOf(
                DefaultClientFactory.DEFAULT.closeAsync(false),
                DefaultClientFactory.INSECURE.closeAsync(false)).handle((unused1, cause) -> {
            if (cause == null) {
                logger.debug("Closed the default client factories");
            } else {
                logger.warn("Failed to close the default client factories:", Exceptions.peel(cause));
            }
            return null;
        });

        if (!(Thread.currentThread() instanceof NonBlocking)) {
            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        closeFuture.get();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException | CancellationException ignored) {
                        // Will be logged by the callback we added above.
                        break;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
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
     * Verifies that client type {@link Class} is supported by this {@link ClientFactory}.
     * Can be used to support multiple {@link ClientFactory}s for a single {@link Scheme}.
     */
    default boolean isClientTypeSupported(Class<?> clientType) {
        return true;
    }

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
     *
     * @param sessionProtocol the {@link SessionProtocol} of the connection
     * @param endpointGroup the {@link EndpointGroup} where {@code endpoint} belongs to.
     * @param endpoint the {@link Endpoint} where a request is being sent.
     *                 {@code null} if the {@link Endpoint} is not known yet.
     */
    ReleasableHolder<EventLoop> acquireEventLoop(SessionProtocol sessionProtocol,
                                                 EndpointGroup endpointGroup,
                                                 @Nullable Endpoint endpoint);

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
     * Creates a new client with the specified {@link ClientBuilderParams}. The client instance returned
     * by this method must be an instance of {@link ClientBuilderParams#clientType()}.
     */
    Object newClient(ClientBuilderParams params);

    /**
     * Returns the number of open connections managed by this {@link ClientFactory}.
     */
    int numConnections();

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

    @Override
    default ClientFactory unwrap() {
        return this;
    }

    /**
     * Makes sure the specified {@link URI} is supported by this {@link ClientFactory}.
     *
     * @param uri the {@link URI} of the server endpoint
     * @return the validated and normalized {@link URI} which always has a non-empty path.
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link URI} is not supported by this
     *                                  {@link ClientFactory}
     */
    default URI validateUri(URI uri) {
        requireNonNull(uri, "uri");

        if (Clients.isUndefinedUri(uri)) {
            // We use a special singleton marker URI for clients that do not explicitly define a
            // host or scheme at construction time.
            // As this isn't created by users, we don't need to normalize it.
            return uri;
        }

        if (uri.getAuthority() == null) {
            throw new IllegalArgumentException("URI with missing authority: " + uri);
        }

        final String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("URI with missing scheme: " + uri);
        }
        final Scheme parsedScheme = Scheme.tryParse(scheme);
        if (parsedScheme == null) {
            throw new IllegalArgumentException("URI with undefined scheme: " + uri);
        }

        final Set<Scheme> supportedSchemes = supportedSchemes();
        if (!supportedSchemes.contains(parsedScheme)) {
            throw new IllegalArgumentException(
                    "URI with unsupported scheme: " + uri + " (expected: " + supportedSchemes + ')');
        }

        final String parsedSchemeStr;
        if (parsedScheme.serializationFormat() == SerializationFormat.NONE) {
            parsedSchemeStr = parsedScheme.sessionProtocol().uriText();
        } else {
            parsedSchemeStr = parsedScheme.uriText();
        }

        final String path = Strings.emptyToNull(uri.getRawPath());
        if (scheme.equals(parsedSchemeStr) && path != null) {
            return uri;
        }

        // Replace the specified URI's scheme with the normalized one.
        try {
            return new URI(parsedSchemeStr, uri.getRawAuthority(),
                           firstNonNull(path, "/"), uri.getRawQuery(),
                           uri.getRawFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Makes sure the specified {@link Scheme} is supported by this {@link ClientFactory}.
     *
     * @param scheme the {@link Scheme} of the server endpoint
     * @return the specified {@link Scheme}
     *
     * @throws IllegalArgumentException if the {@link Scheme} is not supported by this {@link ClientFactory}
     */
    default Scheme validateScheme(Scheme scheme) {
        requireNonNull(scheme, "scheme");

        final Set<Scheme> supportedSchemes = supportedSchemes();
        if (!supportedSchemes.contains(scheme)) {
            throw new IllegalArgumentException(
                    "Unsupported scheme: " + scheme + " (expected: " + supportedSchemes + ')');
        }

        return scheme;
    }

    /**
     * Makes sure the specified {@link ClientBuilderParams} has the {@link Scheme} supported by
     * this {@link ClientFactory}.
     *
     * @return the specified {@link ClientBuilderParams}
     */
    default ClientBuilderParams validateParams(ClientBuilderParams params) {
        requireNonNull(params, "params");
        if (params.options().factory() != this) {
            validateScheme(params.scheme());
        } else {
            // Validated already, unless `ClientBuilderParams` has a bug.
        }
        return params;
    }

    /**
     * Add a shutdown hook to stop this {@link ClientFactory}.
     */
    default void closeOnShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeDefault();
            logger.info("ClientFactory has been closed.");
        }));
    }
}
