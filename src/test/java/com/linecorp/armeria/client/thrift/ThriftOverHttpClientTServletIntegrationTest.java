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
package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.SessionProtocolNegotiationCache;
import com.linecorp.armeria.client.SessionProtocolNegotiationException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Processor;

/**
 * Test to verify interaction between armeria client and official thrift
 * library's {@link TServlet}.
 */
public class ThriftOverHttpClientTServletIntegrationTest {

    private static final Logger logger =
            LoggerFactory.getLogger(ThriftOverHttpClientTServletIntegrationTest.class);

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

    @BeforeClass
    public static void createServer() throws Exception {
        http1server = startHttp1();
        http2server = startHttp2();
    }

    private static Server startHttp1() throws Exception {
        Server server = new Server(0);

        ServletHandler handler = new ServletHandler();
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
        Server server = new Server();
        // Common HTTP configuration.
        HttpConfiguration config = new HttpConfiguration();

        // HTTP/1.1 support.
        HttpConnectionFactory http1 = new HttpConnectionFactory(config);

        // HTTP/2 cleartext support.
        HTTP2CServerConnectionFactory http2c = new HTTP2CServerConnectionFactory(config);

        // Add the connector.
        ServerConnector connector = new ServerConnector(server, http1, http2c);
        connector.setPort(0);
        server.addConnector(connector);

        // Add the servlet.
        ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(newServletHolder(thriftServlet), TSERVLET_PATH);
        server.setHandler(handler);

        // Start the server.
        server.start();
        return server;
    }

    @AfterClass
    public static void destroyServer() throws Exception {
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

    @Before
    public void setup() {
        SessionProtocolNegotiationCache.clear();
        sendConnectionClose.set(false);
    }

    @Test
    public void sendHelloViaHttp1() throws Exception {
        final AtomicReference<SessionProtocol> sessionProtocol = new AtomicReference<>();
        final HelloService.Iface client = newSchemeCapturingClient(http1uri(HTTP), sessionProtocol);

        assertEquals("Hello, old world!", client.hello("old world"));
        assertThat(sessionProtocol.get(), is(H1C));
    }

    /**
     * When an upgrade request is rejected with 'Connection: close', the client should retry the connection
     * attempt silently with explicit H1C.
     */
    @Test
    public void sendHelloViaHttp1WithConnectionClose() throws Exception {
        sendConnectionClose.set(true);

        final AtomicReference<SessionProtocol> sessionProtocol = new AtomicReference<>();
        final HelloService.Iface client = newSchemeCapturingClient(http1uri(HTTP), sessionProtocol);

        assertEquals("Hello, ancient world!", client.hello("ancient world"));
        assertThat(sessionProtocol.get(), is(H1C));
    }

    @Test
    public void sendHelloViaHttp2() throws Exception {
        final AtomicReference<SessionProtocol> sessionProtocol = new AtomicReference<>();
        final HelloService.Iface client = newSchemeCapturingClient(http2uri(HTTP), sessionProtocol);

        assertEquals("Hello, new world!", client.hello("new world"));
        assertThat(sessionProtocol.get(), is(H2C));
    }

    /**
     * {@link SessionProtocolNegotiationException} should be raised if a user specified H2C explicitly and the
     * client failed to upgrade.
     */
    @Test
    public void testRejectedUpgrade() throws Exception {
        final InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", http1Port());

        assertFalse(SessionProtocolNegotiationCache.isUnsupported(remoteAddress, H2C));

        final HelloService.Iface client =
                Clients.newClient(http1uri(H2C), HelloService.Iface.class);

        try {
            client.hello("unused");
            fail();
        } catch (SessionProtocolNegotiationException e) {
            // Test if a failed upgrade attempt triggers an exception with
            // both 'expected' and 'actual' protocols.
            assertThat(e.expected(), is(H2C));
            assertThat(e.actual().orElse(null), is(H1C));
            // .. and if the negotiation cache is updated.
            assertTrue(SessionProtocolNegotiationCache.isUnsupported(remoteAddress, H2C));
        }

        try {
            client.hello("unused");
            fail();
        } catch (SessionProtocolNegotiationException e) {
            // Test if no upgrade attempt is made thanks to the cache.
            assertThat(e.expected(), is(H2C));
            // It has no idea about the actual protocol, because it did not create any connection.
            assertThat(e.actual().isPresent(), is(false));
        }
    }

    private static HelloService.Iface newSchemeCapturingClient(
            String uri, AtomicReference<SessionProtocol> sessionProtocol) {

        return new ClientBuilder(uri)
                .decorator(ThriftCall.class, ThriftReply.class,
                           c -> new SessionProtocolCapturer<>(c, sessionProtocol))
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

    private static class SessionProtocolCapturer<I extends Request, O extends Response>
            extends DecoratingClient<I, O, I, O> {

        private final AtomicReference<SessionProtocol> sessionProtocol;

        @SuppressWarnings("unchecked")
        SessionProtocolCapturer(Client<? super I, ? extends O> delegate,
                                AtomicReference<SessionProtocol> sessionProtocol) {
            super(delegate);
            this.sessionProtocol = sessionProtocol;
        }

        @Override
        public O execute(ClientRequestContext ctx, I req) throws Exception {
            ctx.requestLogFuture()
               .thenAccept(log -> sessionProtocol.set(log.scheme().sessionProtocol()))
               .exceptionally(CompletionActions::log);
            return delegate().execute(ctx, req);
        }
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
