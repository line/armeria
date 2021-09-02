/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A delegate that has common testing methods of {@link Server}.
 */
public abstract class ServerRuleDelegate {

    private final AtomicReference<Server> server = new AtomicReference<>();
    private final boolean autoStart;

    /**
     * Creates a new instance.
     *
     * @param autoStart {@code true} if the {@link Server} should start automatically.
     *                  {@code false} if the {@link Server} should start when a user calls {@link #start()}.
     */
    protected ServerRuleDelegate(boolean autoStart) {
        this.autoStart = autoStart;
    }

    /**
     * Calls {@link #start()} if auto-start is enabled.
     */
    public void before() throws Throwable {
        if (autoStart) {
            start();
        }
    }

    /**
     * Calls {@link #stop()}, without waiting until the {@link Server} is stopped completely.
     */
    public void after() {
        stop();
    }

    /**
     * Starts the {@link Server} configured by {@link #configure(ServerBuilder)}.
     * If the {@link Server} has been started up already, the existing {@link Server} is returned.
     * Note that this operation blocks until the {@link Server} finished the start-up.
     *
     * @return the started {@link Server}
     */
    public Server start() {
        final Server oldServer = server.get();
        if (!isStopped(oldServer)) {
            return oldServer;
        }

        final ServerBuilder sb = Server.builder();
        try {
            configure(sb);
        } catch (Exception e) {
            throw new IllegalStateException("failed to configure a Server", e);
        }

        final Server server = sb.build();
        server.start().join();

        this.server.set(server);
        return server;
    }

    /**
     * Configures the {@link Server} with the given {@link ServerBuilder}.
     */
    public abstract void configure(ServerBuilder sb) throws Exception;

    /**
     * Stops the {@link Server} asynchronously.
     *
     * @return the {@link CompletableFuture} that will complete when the {@link Server} is stopped.
     */
    public CompletableFuture<Void> stop() {
        @Nullable
        final Server server = this.server.getAndSet(null);
        if (server == null || server.activePorts().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return server.stop();
    }

    /**
     * Returns the started {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started
     */
    public Server server() {
        final Server server = this.server.get();
        if (isStopped(server)) {
            throw new IllegalStateException("server did not start.");
        }
        return server;
    }

    private static boolean isStopped(@Nullable Server server) {
        return server == null || server.activePorts().isEmpty();
    }

    /**
     * Returns the HTTP port number of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public int httpPort() {
        return port(HTTP);
    }

    /**
     * Returns the HTTPS port number of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public int httpsPort() {
        return port(HTTPS);
    }

    /**
     * Returns the port number of the {@link Server} for the specified {@link SessionProtocol}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open a port of the
     *                               specified protocol.
     */
    public int port(SessionProtocol protocol) {
        return server().activeLocalPort(protocol);
    }

    /**
     * Returns {@code true} if the {@link Server} is started and it has an HTTP port open.
     */
    public boolean hasHttp() {
        return hasSessionProtocol(HTTP);
    }

    /**
     * Returns {@code true} if the {@link Server} is started and it has an HTTPS port open.
     */
    public boolean hasHttps() {
        return hasSessionProtocol(HTTPS);
    }

    private boolean hasSessionProtocol(SessionProtocol protocol) {
        @Nullable
        final Server server = this.server.get();
        return server != null &&
               server.activePorts().values().stream()
                     .anyMatch(port -> port.hasProtocol(protocol));
    }

    /**
     * Returns the {@link Endpoint} of the specified {@link SessionProtocol} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open the port for the specified {@link SessionProtocol}.
     */
    public Endpoint endpoint(SessionProtocol protocol) {
        ensureStarted();
        return Endpoint.of("127.0.0.1", port(protocol));
    }

    /**
     * Returns the HTTP {@link Endpoint} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTP port.
     */
    public Endpoint httpEndpoint() {
        return endpoint(HTTP);
    }

    /**
     * Returns the HTTPS {@link Endpoint} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTPS port.
     */
    public Endpoint httpsEndpoint() {
        return endpoint(HTTPS);
    }

    /**
     * Returns the {@link URI} for the {@link Server} of the specified {@link SessionProtocol}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open the port for the specified {@link SessionProtocol}.
     */
    public URI uri(SessionProtocol protocol) {
        return uri(protocol, SerializationFormat.NONE);
    }

    /**
     * Returns the {@link URI} for the {@link Server} of the specified {@link SessionProtocol} and
     * {@link SerializationFormat}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open the port for the specified {@link SessionProtocol}.
     */
    public URI uri(SessionProtocol protocol, SerializationFormat format) {
        requireNonNull(protocol, "protocol");
        requireNonNull(format, "format");

        ensureStarted();

        final int port;
        if (!protocol.isTls() && hasHttp()) {
            port = httpPort();
        } else if (protocol.isTls() && hasHttps()) {
            port = httpsPort();
        } else {
            throw new IllegalStateException("can't find the specified port");
        }

        final String uriStr = protocol.uriText() + "://127.0.0.1:" + port;
        if (format == SerializationFormat.NONE) {
            return URI.create(uriStr);
        } else {
            return URI.create(format.uriText() + '+' + uriStr);
        }
    }

    /**
     * Returns the HTTP {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTP port.
     */
    public URI httpUri() {
        return uri(HTTP);
    }

    /**
     * Returns the HTTP {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTP port.
     */
    public URI httpUri(SerializationFormat format) {
        return uri(HTTP, format);
    }

    /**
     * Returns the HTTPS {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTPS port.
     */
    public URI httpsUri() {
        return uri(HTTPS);
    }

    /**
     * Returns the HTTPS {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTPS port.
     */
    public URI httpsUri(SerializationFormat format) {
        return uri(HTTPS, format);
    }

    /**
     * Returns the {@link InetSocketAddress} of the specified {@link SessionProtocol} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open a port for the specified {@link SessionProtocol}.
     */
    public InetSocketAddress socketAddress(SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");
        ensureStarted();
        return new InetSocketAddress("127.0.0.1", port(protocol));
    }

    /**
     * Returns the HTTP {@link InetSocketAddress} of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public InetSocketAddress httpSocketAddress() {
        return socketAddress(HTTP);
    }

    /**
     * Returns the HTTPS {@link InetSocketAddress} of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public InetSocketAddress httpsSocketAddress() {
        return socketAddress(HTTPS);
    }

    private void ensureStarted() {
        // This will ensure that the server has started.
        server();
    }
}
