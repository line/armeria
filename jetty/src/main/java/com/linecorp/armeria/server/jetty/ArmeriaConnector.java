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

package com.linecorp.armeria.server.jetty;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

import io.netty.util.concurrent.GlobalEventExecutor;

final class ArmeriaConnector extends ContainerLifeCycle implements Connector {

    private static final String PROTOCOL_NAME = "armeria";
    private static final List<String> PROTOCOL_NAMES = Collections.singletonList(PROTOCOL_NAME);

    private final Server server;
    private final HttpConfiguration httpConfig;
    private final Executor executor;
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final ArmeriaConnectionFactory connectionFactory;
    private final Collection<ConnectionFactory> connectionFactories;

    ArmeriaConnector(Server server) {
        this.server = server;
        executor = server.getThreadPool();

        final HttpConfiguration httpConfig = server.getBean(HttpConfiguration.class);
        this.httpConfig = httpConfig != null ? httpConfig : new HttpConfiguration();

        final Scheduler scheduler = server.getBean(Scheduler.class);
        this.scheduler = scheduler != null ? scheduler : new ScheduledExecutorScheduler();

        final ByteBufferPool byteBufferPool = server.getBean(ByteBufferPool.class);
        this.byteBufferPool = byteBufferPool != null ? byteBufferPool : new ArrayByteBufferPool();

        addBean(server, false);
        addBean(executor);
        unmanage(executor);
        addBean(this.httpConfig);
        addBean(this.scheduler);
        addBean(this.byteBufferPool);

        connectionFactory = new ArmeriaConnectionFactory();
        connectionFactories = Collections.singleton(connectionFactory);
    }

    @Override
    public Object getTransport() {
        return this;
    }

    @Override
    public Server getServer() {
        return server;
    }

    public HttpConfiguration getHttpConfiguration() {
        return httpConfig;
    }

    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public Scheduler getScheduler() {
        return scheduler;
    }

    @Override
    public ByteBufferPool getByteBufferPool() {
        return byteBufferPool;
    }

    @Override
    public ConnectionFactory getConnectionFactory(String nextProtocol) {
        return PROTOCOL_NAME.equals(nextProtocol) ? connectionFactory : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConnectionFactory(Class<T> factoryType) {
        return factoryType == ArmeriaConnectionFactory.class ? (T) connectionFactory : null;
    }

    @Override
    public ConnectionFactory getDefaultConnectionFactory() {
        return connectionFactory;
    }

    @Override
    public Collection<ConnectionFactory> getConnectionFactories() {
        return connectionFactories;
    }

    @Override
    public List<String> getProtocols() {
        return PROTOCOL_NAMES;
    }

    @Override
    public long getIdleTimeout() {
        return 0;
    }

    @Override
    public Collection<EndPoint> getConnectedEndPoints() {
        return Collections.emptyList();
    }

    @Override
    public String getName() {
        return "armeria";
    }

    @Override
    public Future<Void> shutdown() {
        return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
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
