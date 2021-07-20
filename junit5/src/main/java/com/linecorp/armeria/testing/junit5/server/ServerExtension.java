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

package com.linecorp.armeria.testing.junit5.server;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.ServerRuleDelegate;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

/**
 * An {@link Extension} that allows easy set-up and tear-down of a {@link Server}.
 */
public abstract class ServerExtension extends AbstractAllOrEachExtension {
    private final ServiceRequestContextCaptor contextCaptor;

    private final ServerRuleDelegate delegate;

    /**
     * Creates a new instance with auto-start enabled.
     */
    protected ServerExtension() {
        this(true);
    }

    /**
     * Creates a new instance.
     *
     * @param autoStart {@code true} if the {@link Server} should start automatically.
     *                  {@code false} if the {@link Server} should start when a user calls {@link #start()}.
     */
    protected ServerExtension(boolean autoStart) {
        contextCaptor = new ServiceRequestContextCaptor();
        delegate = new ServerRuleDelegate(autoStart) {
            @Override
            public void configure(ServerBuilder sb) throws Exception {
                sb.decorator(contextCaptor.decorator(ServerExtension.this::shouldCapture));
                ServerExtension.this.configure(sb);
            }
        };
    }

    /**
     * Calls {@link #start()} if auto-start is enabled.
     */
    @Override
    public void before(ExtensionContext context) throws Exception {
        try {
            delegate.before();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set up before callback", t);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        super.beforeEach(context);
        contextCaptor.clear();
    }

    /**
     * Calls {@link #stop()}, without waiting until the {@link Server} is stopped completely.
     */
    @Override
    public void after(ExtensionContext context) throws Exception {
        delegate.after();
    }

    /**
     * Starts the {@link Server} configured by {@link #configure(ServerBuilder)}.
     * If the {@link Server} has been started up already, the existing {@link Server} is returned.
     * Note that this operation blocks until the {@link Server} finished the start-up.
     *
     * @return the started {@link Server}
     */
    public Server start() {
        return delegate.start();
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
        return delegate.stop();
    }

    /**
     * Returns the started {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started
     */
    public Server server() {
        return delegate.server();
    }

    /**
     * Returns the HTTP port number of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public int httpPort() {
        return delegate.httpPort();
    }

    /**
     * Returns the HTTPS port number of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public int httpsPort() {
        return delegate.httpsPort();
    }

    /**
     * Returns the port number of the {@link Server} for the specified {@link SessionProtocol}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open a port of the
     *                               specified protocol.
     */
    public int port(SessionProtocol protocol) {
        return delegate.port(protocol);
    }

    /**
     * Returns {@code true} if the {@link Server} is started and it has an HTTP port open.
     */
    public boolean hasHttp() {
        return delegate.hasHttp();
    }

    /**
     * Returns {@code true} if the {@link Server} is started and it has an HTTPS port open.
     */
    public boolean hasHttps() {
        return delegate.hasHttps();
    }

    /**
     * Returns the {@link Endpoint} of the specified {@link SessionProtocol} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open the port for the specified {@link SessionProtocol}.
     */
    public Endpoint endpoint(SessionProtocol protocol) {
        return delegate.endpoint(protocol);
    }

    /**
     * Returns the HTTP {@link Endpoint} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTP port.
     */
    public Endpoint httpEndpoint() {
        return delegate.httpEndpoint();
    }

    /**
     * Returns the HTTPS {@link Endpoint} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTPS port.
     */
    public Endpoint httpsEndpoint() {
        return delegate.httpsEndpoint();
    }

    /**
     * Returns the {@link URI} for the {@link Server} of the specified {@link SessionProtocol}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open the port for the specified {@link SessionProtocol}.
     */
    public URI uri(SessionProtocol protocol) {
        return delegate.uri(protocol);
    }

    /**
     * Returns the {@link URI} for the {@link Server} of the specified {@link SessionProtocol} and
     * {@link SerializationFormat}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open the port for the specified {@link SessionProtocol}.
     */
    public URI uri(SessionProtocol protocol, SerializationFormat format) {
        return delegate.uri(protocol, format);
    }

    /**
     * Returns the HTTP {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTP port.
     */
    public URI httpUri() {
        return delegate.httpUri();
    }

    /**
     * Returns the HTTP {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTP port.
     */
    public URI httpUri(SerializationFormat format) {
        return delegate.httpUri(format);
    }

    /**
     * Returns the HTTPS {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTPS port.
     */
    public URI httpsUri() {
        return delegate.httpsUri();
    }

    /**
     * Returns the HTTPS {@link URI} for the {@link Server}.
     *
     * @return the absolute {@link URI} without a path.
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open an HTTPS port.
     */
    public URI httpsUri(SerializationFormat format) {
        return delegate.httpsUri(format);
    }

    /**
     * Returns the {@link InetSocketAddress} of the specified {@link SessionProtocol} for the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or
     *                               it did not open a port for the specified {@link SessionProtocol}.
     */
    public InetSocketAddress socketAddress(SessionProtocol protocol) {
        return delegate.socketAddress(protocol);
    }

    /**
     * Returns the HTTP {@link InetSocketAddress} of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTP port
     */
    public InetSocketAddress httpSocketAddress() {
        return delegate.httpSocketAddress();
    }

    /**
     * Returns the HTTPS {@link InetSocketAddress} of the {@link Server}.
     *
     * @throws IllegalStateException if the {@link Server} is not started or it did not open an HTTPS port
     */
    public InetSocketAddress httpsSocketAddress() {
        return delegate.httpsSocketAddress();
    }

    /**
     * Returns the {@link ServiceRequestContextCaptor} that captures all the {@link
     * ServiceRequestContext}s in this {@link Server}.
     */
    public final ServiceRequestContextCaptor requestContextCaptor() {
        return contextCaptor;
    }

    /**
     * Determines whether the {@link ServiceRequestContext} should be captured or not.
     * This method returns {@code true} by default. Override it to capture the contexts
     * selectively.
     */
    protected boolean shouldCapture(ServiceRequestContext ctx) {
        return true;
    }
}
