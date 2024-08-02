/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.thrift.server.TServlet;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import testing.thrift.main.HelloService.Processor;

final class ServletTestUtils {

    private ServletTestUtils() {}

    static final String TSERVLET_PATH = "/thrift";

    @SuppressWarnings("unchecked")
    private static final Servlet thriftServlet =
            new TServlet(new Processor(name -> "Hello, " + name + '!'), ThriftProtocolFactories.binary(0, 0));

    private static final Servlet rootServlet = new HttpServlet() {
        private static final long serialVersionUID = 6765028749367036441L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            resp.setStatus(404);
            resp.setContentLength(0);
        }
    };

    static Server startHttp1(AtomicBoolean sendConnectionClose) throws Exception {
        final Server server = new Server(0);

        final ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(newServletHolder(thriftServlet), TSERVLET_PATH);
        handler.addServletWithMapping(newServletHolder(rootServlet), "/");
        handler.addFilterWithMapping(new FilterHolder(new ConnectionCloseFilter(sendConnectionClose)), "/*",
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

    static Server startHttp2() throws Exception {
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

    private static ServletHolder newServletHolder(Servlet rootServlet) {
        final ServletHolder holder = new ServletHolder(rootServlet);
        holder.setInitParameter("cacheControl", "max-age=0, public");
        return holder;
    }

    private static class ConnectionCloseFilter implements Filter {
        private final AtomicBoolean sendConnectionClose;

        ConnectionCloseFilter(AtomicBoolean sendConnectionClose) {
            this.sendConnectionClose = sendConnectionClose;
        }

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
