/*
 * Copyright 2015 LINE Corporation
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.Servlet;

import org.apache.thrift.server.TServlet;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.DecoratingClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.buffer.ByteBuf;

/**
 * Test to verify interaction between armeria client and official thrift
 * library's {@link TServlet}.
 */
public class ThriftOverHttpClientTServletIntegrationTest {

    private static final HelloService.Iface helloHandler = name -> "Hello, " + name + '!';

    private static Servlet servlet;
    private static Server http1server;
    private static Server http2server;

    @BeforeClass
    public static void createServer() throws Exception {
        servlet = new TServlet(new HelloService.Processor(helloHandler), ThriftProtocolFactories.BINARY);

        http1server = startHttp1();
        http2server = startHttp2();
    }

    private static Server startHttp1() throws Exception {
        Server server = new Server(0);
        ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(new ServletHolder(servlet), "/thrift");
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
        handler.addServletWithMapping(new ServletHolder(servlet), "/thrift");
        server.setHandler(handler);

        // Start the server.
        server.start();
        return server;
    }

    @AfterClass
    public static void destroyServer() throws Exception {
        if (http1server != null) {
            http1server.stop();
        }
        if (http2server != null) {
            http2server.stop();
        }
    }

    @Test
    public void sendHelloViaHttp1() throws Exception {
        AtomicReference<Scheme> scheme = new AtomicReference<>();
        HelloService.Iface client = Clients.newClient(
                http1uri("/thrift"), HelloService.Iface.class,
                ClientOption.CLIENT_CODEC_DECORATOR.newValue(c -> new SchemeCapturer(c, scheme)));

        assertEquals("Hello, old world!", client.hello("old world"));
        assertThat(scheme.get().sessionProtocol(), is(SessionProtocol.H1C));
    }

    @Test
    public void sendHelloViaHttp2() throws Exception {
        AtomicReference<Scheme> scheme = new AtomicReference<>();
        HelloService.Iface client = Clients.newClient(
                http2uri("/thrift"), HelloService.Iface.class,
                ClientOption.CLIENT_CODEC_DECORATOR.newValue(c -> new SchemeCapturer(c, scheme)));

        assertEquals("Hello, new world!", client.hello("new world"));
        assertThat(scheme.get().sessionProtocol(), is(SessionProtocol.H2C));
    }

    private static String http1uri(String path) {
        int port = ((NetworkConnector) http1server.getConnectors()[0]).getLocalPort();
        return "tbinary+http://127.0.0.1:" + port + path;
    }

    private static String http2uri(String path) {
        int port = ((NetworkConnector) http2server.getConnectors()[0]).getLocalPort();
        return "tbinary+http://127.0.0.1:" + port + path;
    }

    private static class SchemeCapturer extends DecoratingClientCodec {

        private final AtomicReference<Scheme> scheme;

        SchemeCapturer(ClientCodec delegate, AtomicReference<Scheme> scheme) {
            super(delegate);
            this.scheme = scheme;
        }

        @Override
        public <T> T decodeResponse(ServiceInvocationContext ctx,
                                    ByteBuf content, Object originalResponse) throws Exception {
            if (!scheme.compareAndSet(null, ctx.scheme())) {
                throw new IllegalStateException("decoded more than one response");
            }

            return super.decodeResponse(ctx, content, originalResponse);
        }
    }
}
