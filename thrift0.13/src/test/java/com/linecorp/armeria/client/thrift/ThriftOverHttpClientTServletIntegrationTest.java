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
package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.thrift.server.TServlet;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.SessionProtocolNegotiationCache;
import com.linecorp.armeria.client.SessionProtocolNegotiationException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Processor;

/**
 * Test to verify interaction between armeria client and official thrift
 * library's {@link TServlet}.
 */
class ThriftOverHttpClientTServletIntegrationTest {

    private static final Logger logger =
            LoggerFactory.getLogger(ThriftOverHttpClientTServletIntegrationTest.class);

    private static final int MAX_RETRIES = 9;

    private static final String TSERVLET_PATH = "/thrift";

    @SuppressWarnings("unchecked")
    private static final Servlet thriftServlet =
            new TServlet(new Processor(name -> "Hello, " + name + '!'), ThriftProtocolFactories.BINARY);

    private static final Servlet rootServlet = new HttpServlet() {
        private static final long serialVersionUID = 6765028749367036441L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            resp.setStatus(404);
            resp.setContentLength(0);
        }
    };

    private static final AtomicBoolean sendConnectionClose = new AtomicBoolean();

    private static Server http1server;
    private static Server http2server;

    @BeforeAll
    static void createServer() throws Exception {
        http1server = startHttp1();
        http2server = startHttp2();
    }

    private static Server startHttp1() throws Exception {
        final Server server = new Server(0);

        final ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(newServletHolder(thriftServlet), TSERVLET_PATH);
        handler.addServletWithMapping(newServletHolder(rootServlet), "/");
        handler.addFilterWithMapping(new FilterHolder(new ConnectionCloseFilter()), "/*",
                                     EnumSet.of(DispatcherType.REQUEST));

        server.setHandler(handler);

        for (Connector c : server.getConnectors()) {
            for (ConnectionFactory f : c.getConnectionFactories()) {
                for (String p : f.getProtocols()) {
                    if (p.startsWith("h2c")) {
                        fail("Attempted to create a Jetty server without HTTP/2 support, but failed: " +
                             f.getProtocols());
                    }
                }
            }
        }

        server.start();
        return server;
    }

    @Nonnull
    private static ServletHolder newServletHolder(Servlet rootServlet) {
        final ServletHolder holder = new ServletHolder(rootServlet);
        holder.setInitParameter("cacheControl", "max-age=0, public");
        return holder;
    }

    private static Server startHttp2() throws Exception {
        final Server server = new Server();
        // Common HTTP configuration.
        final HttpConfiguration config = new HttpConfiguration();

        // HTTP/1.1 support.
        final HttpConnectionFactory http1 = new HttpConnectionFactory(config);

        // HTTP/2 cleartext support.
        final HTTP2CServerConnectionFactory http2c = new HTTP2CServerConnectionFactory(config);

        // Add the connector.
        final ServerConnector connector = new ServerConnector(server, http1, http2c);
        connector.setPort(0);
        server.addConnector(connector);

        // Add the servlet.
        final ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(newServletHolder(thriftServlet), TSERVLET_PATH);
        server.setHandler(handler);

        // Start the server.
        server.start();
        return server;
    }

    @AfterAll
    static void destroyServer() throws Exception {
        CompletableFuture.runAsync(() -> {
            if (http1server != null) {
                try {
                    http1server.stop();
                } catch (Exception e) {
                    logger.warn("Failed to stop HTTP/1 server", e);
                }
            }
            if (http2server != null) {
                try {
                    http2server.stop();
                } catch (Exception e) {
                    logger.warn("Failed to stop HTTP/2 server", e);
                }
            }
        });
    }

    @BeforeEach
    void setUp() {
        SessionProtocolNegotiationCache.clear();
        sendConnectionClose.set(false);
    }

    @Test
    void sendHelloViaHttp1() throws Exception {
        final AtomicReference<SessionProtocol> sessionProtocol = new AtomicReference<>();
        final HelloService.Iface client = newSchemeCapturingClient(http1uri(HTTP), sessionProtocol);

        for (int i = 0; i <= MAX_RETRIES; i++) {
            try {
                assertThat(client.hello("old world")).isEqualTo("Hello, old world!");
                assertThat(sessionProtocol.get()).isEqualTo(H1C);
                if (i != 0) {
                    logger.warn("Succeeded after {} retries.", i);
                }
                break;
            } catch (ClosedSessionException e) {
                // Flaky test; try again.
                // FIXME(trustin): Fix flakiness.
                if (i == MAX_RETRIES) {
                    throw e;
                }
            }
        }
    }

    /**
     * When an upgrade request is rejected with 'Connection: close', the client should retry the connection
     * attempt silently with explicit H1C.
     */
    @Test
    void sendHelloViaHttp1WithConnectionClose() throws Exception {
        sendConnectionClose.set(true);

        final AtomicReference<SessionProtocol> sessionProtocol = new AtomicReference<>();
        final HelloService.Iface client = newSchemeCapturingClient(http1uri(HTTP), sessionProtocol);

        for (int i = 0; i <= MAX_RETRIES; i++) {
            try {
                assertThat(client.hello("ancient world")).isEqualTo("Hello, ancient world!");
                assertThat(sessionProtocol.get()).isEqualTo(H1C);
                if (i != 0) {
                    logger.warn("Succeeded after {} retries.", i);
                }
                break;
            } catch (ClosedSessionException e) {
                // Flaky test; try again.
                // FIXME(trustin): Fix flakiness.
                if (i == MAX_RETRIES) {
                    throw e;
                }
            }
        }
    }

    @Test
    void sendHelloViaHttp2() throws Exception {
        final AtomicReference<SessionProtocol> sessionProtocol = new AtomicReference<>();
        final HelloService.Iface client = newSchemeCapturingClient(http2uri(HTTP), sessionProtocol);

        assertThat(client.hello("new world")).isEqualTo("Hello, new world!");
        assertThat(sessionProtocol.get()).isEqualTo(H2C);
    }

    /**
     * {@link SessionProtocolNegotiationException} should be raised if a user specified H2C explicitly and the
     * client failed to upgrade.
     */
    @Test
    void testRejectedUpgrade() throws Exception {
        final InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", http1Port());

        assertThat(SessionProtocolNegotiationCache.isUnsupported(remoteAddress, H2C)).isFalse();

        final HelloService.Iface client =
                Clients.newClient(http1uri(H2C), HelloService.Iface.class);

        assertThatThrownBy(() -> client.hello("unused"))
                .isInstanceOfSatisfying(UnprocessedRequestException.class, e -> {
                    assertThat(e).hasCauseInstanceOf(SessionProtocolNegotiationException.class);
                    final SessionProtocolNegotiationException cause =
                            (SessionProtocolNegotiationException) e.getCause();

                    // Test if a failed upgrade attempt triggers an exception with
                    // both 'expected' and 'actual' protocols.
                    assertThat(cause.expected()).isEqualTo(H2C);
                    assertThat(cause.actual()).isEqualTo(H1C);
                    // .. and if the negotiation cache is updated.
                    assertThat(SessionProtocolNegotiationCache.isUnsupported(remoteAddress, H2C)).isTrue();
                });

        assertThatThrownBy(() -> client.hello("unused"))
                .isInstanceOfSatisfying(UnprocessedRequestException.class, e -> {
                    assertThat(e).hasCauseInstanceOf(SessionProtocolNegotiationException.class);
                    final SessionProtocolNegotiationException cause =
                            (SessionProtocolNegotiationException) e.getCause();
                    // Test if no upgrade attempt is made thanks to the cache.
                    assertThat(cause.expected()).isEqualTo(H2C);
                    // It has no idea about the actual protocol, because it did not create any connection.
                    assertThat(cause.actual()).isNull();
                });
    }

    private static HelloService.Iface newSchemeCapturingClient(
            String uri, AtomicReference<SessionProtocol> sessionProtocol) {

        return Clients.builder(uri)
                      .rpcDecorator((delegate, ctx, req) -> {
                          ctx.log().whenAvailable(RequestLogProperty.SESSION)
                             .thenAccept(log -> sessionProtocol.set(log.sessionProtocol()));
                          return delegate.execute(ctx, req);
                      })
                      .build(HelloService.Iface.class);
    }

    private static String http1uri(SessionProtocol protocol) {
        return "tbinary+" + protocol.uriText() + "://127.0.0.1:" + http1Port() + TSERVLET_PATH;
    }

    private static int http1Port() {
        return ((NetworkConnector) http1server.getConnectors()[0]).getLocalPort();
    }

    private static String http2uri(SessionProtocol protocol) {
        return "tbinary+" + protocol.uriText() + "://127.0.0.1:" + http2Port() + TSERVLET_PATH;
    }

    private static int http2Port() {
        return ((NetworkConnector) http2server.getConnectors()[0]).getLocalPort();
    }

    private static class ConnectionCloseFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            chain.doFilter(request, response);

            if (sendConnectionClose.get()) {
                ((HttpServletResponse) response).setHeader("Connection", "close");
            }
        }

        @Override
        public void destroy() {}
    }
}
