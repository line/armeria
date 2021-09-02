/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.jetty;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;

import io.netty.util.concurrent.GlobalEventExecutor;

final class ArmeriaConnector extends ServerConnector {

    private static final String PROTOCOL_NAME = "armeria";
    private static final List<String> PROTOCOL_NAMES = Collections.singletonList(PROTOCOL_NAME);

    private final com.linecorp.armeria.server.Server armeriaServer;
    private final HttpConfiguration httpConfig;

    private volatile boolean isShutdown;

    ArmeriaConnector(Server server, com.linecorp.armeria.server.Server armeriaServer) {
        super(server, -1, -1, new ArmeriaConnectionFactory());

        this.armeriaServer = armeriaServer;

        @Nullable
        final HttpConfiguration httpConfig = server.getBean(HttpConfiguration.class);
        this.httpConfig = httpConfig != null ? httpConfig : new HttpConfiguration();
        addBean(this.httpConfig);

        setDefaultProtocol(PROTOCOL_NAME);
    }

    @Override
    public Object getTransport() {
        return this;
    }

    @Override
    public String getHost() {
        //noinspection ConstantConditions
        if (armeriaServer == null) {
            // This method could be called during ServerConnector construction (for diagnostic purposes)
            // BEFORE {@code armeriaServer} gets assigned. In such case case,
            // return some reasonable mockup value in order to prevent NPE.
            return SystemInfo.hostname();
        }
        return armeriaServer.defaultHostname();
    }

    @Override
    public int getPort() {
        return getLocalPort();
    }

    @Override
    public int getLocalPort() {
        //noinspection ConstantConditions
        if (armeriaServer == null) {
            // This method could be called during ServerConnector construction (for diagnostic purposes),
            // BEFORE {@code armeriaServer} gets assigned. In such case case,
            // return some reasonable mockup value in order to prevent NPE.
            return -1;
        }
        try {
            return armeriaServer.activeLocalPort();
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    public HttpConfiguration getHttpConfiguration() {
        return httpConfig;
    }

    @Override
    public String getName() {
        return PROTOCOL_NAME;
    }

    @Override
    public boolean isOpen() {
        return !isShutdown();
    }

    @Override
    protected void doStart() {}

    @Override
    protected void doStop() {}

    @Override
    public void open() {}

    @Override
    public void open(ServerSocketChannel acceptChannel) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected ServerSocketChannel openAcceptChannel() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}

    @Override
    public Future<Void> shutdown() {
        isShutdown = true;
        return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
    }

    @Override
    public boolean isShutdown() {
        return isShutdown;
    }

    private static final class ArmeriaConnectionFactory implements ConnectionFactory {
        @Override
        public String getProtocol() {
            return PROTOCOL_NAME;
        }

        @Override
        public List<String> getProtocols() {
            return PROTOCOL_NAMES;
        }

        @Override
        public Connection newConnection(Connector connector, EndPoint endPoint) {
            throw new UnsupportedOperationException();
        }
    }
}
