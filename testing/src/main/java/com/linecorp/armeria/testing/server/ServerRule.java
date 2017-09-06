/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.testing.server;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A {@link TestRule} that allows easy set-up and tear-down of a {@link Server}.
 */
public abstract class ServerRule extends ExternalResource {

    private final AtomicReference<Server> server = new AtomicReference<>();
    private final boolean autoStart;

    /**
     * Creates a new instance with auto-start enabled.
     */
    protected ServerRule() {
        this(true);
    }

    /**
     * Creates a new instance.
     *
     * @param autoStart {@code true} if the {@link Server} should start automatically.
     *                  {@code false} if the {@link Server} should start when a user calls {@link #start()}.
     */
    protected ServerRule(boolean autoStart) {
        this.autoStart = autoStart;
    }

    /**
     * Calls {@link #start()} if auto-start is enabled.
     */
    @Override
    protected void before() throws Throwable {
        if (autoStart) {
            start();
        }
    }

    /**
     * Calls {@link #stop()}, without waiting until the {@link Server} is stopped completely.
     */
    @Override
    protected void after() {
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

        final ServerBuilder sb = new ServerBuilder();
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
    protected abstract void configure(ServerBuilder sb) throws Exception;

    /**
     * Stops the {@link Server} asynchronously.
     *
     * @return the {@link CompletableFuture} that will complete when the {@link Server} is stopped.
     */
    public CompletableFuture<Void> stop() {
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

    private static boolean isStopped(Server server) {
        return server == null || server.activePorts().isEmpty();
    }

    /**
     * Returns the HTTP port number of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public int httpPort() {
        return server().activePorts().values().stream()
                       .filter(p1 -> p1.protocol() == HTTP).findAny()
                       .flatMap(p -> Optional.of(p.localAddress().getPort()))
                       .orElseThrow(() -> new IllegalStateException("HTTP port not open"));
    }

    /**
     * Returns the HTTPS port number of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public int httpsPort() {
        return server().activePorts().values().stream()
                       .filter(p1 -> p1.protocol() == HTTPS).findAny()
                       .flatMap(p -> Optional.of(p.localAddress().getPort()))
                       .orElseThrow(() -> new IllegalStateException("HTTPS port not open"));
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
        final Server server = this.server.get();
        return server != null &&
               server.activePorts().values().stream()
                     .anyMatch(port -> port.protocol() == protocol);
    }

    /**
     * Returns the HTTP or HTTPS URI for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it opened neither HTTP nor HTTPS port
     */
    public String uri(String path) {
        // This will ensure that the server has started.
        server();

        if (hasHttp()) {
            return httpUri(path);
        }

        if (hasHttps()) {
            return httpsUri(path);
        }

        throw new IllegalStateException("can't find a useful active port");
    }

    /**
     * Returns the HTTP or HTTPS URI for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it opened neither HTTP nor HTTPS port
     */
    public String uri(SerializationFormat serializationFormat, String path) {
        requireNonNull(serializationFormat, "serializationFormat");
        return serializationFormat.uriText() + '+' + uri(path);
    }

    /**
     * Returns the HTTP URI for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public String httpUri(String path) {
        validatePath(path);
        return "http://127.0.0.1:" + httpPort() + path;
    }

    /**
     * Returns the HTTP URI for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public String httpUri(SerializationFormat serializationFormat, String path) {
        requireNonNull(serializationFormat, "serializationFormat");
        return serializationFormat.uriText() + '+' + httpUri(path);
    }

    /**
     * Returns the HTTPS URI for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public String httpsUri(String path) {
        validatePath(path);
        return "https://127.0.0.1:" + httpsPort() + path;
    }

    /**
     * Returns the HTTPS URI for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public String httpsUri(SerializationFormat serializationFormat, String path) {
        requireNonNull(serializationFormat, "serializationFormat");
        return serializationFormat.uriText() + '+' + httpsUri(path);
    }

    /**
     * Returns the HTTP {@link InetSocketAddress} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public InetSocketAddress httpSocketAddress() {
        return new InetSocketAddress("127.0.0.1", httpPort());
    }

    /**
     * Returns the HTTPS {@link InetSocketAddress} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public InetSocketAddress httpsSocketAddress() {
        return new InetSocketAddress("127.0.0.1", httpsPort());
    }

    private static void validatePath(String path) {
        if (!requireNonNull(path, "path").startsWith("/")) {
            throw new IllegalArgumentException("path: " + path + " (expected: an absolute path)");
        }
    }
}
