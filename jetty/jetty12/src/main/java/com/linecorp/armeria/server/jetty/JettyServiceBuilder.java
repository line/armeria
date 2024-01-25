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

package com.linecorp.armeria.server.jetty;

import static java.util.Objects.requireNonNull;

import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Handler.Singleton;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * Builds a {@link JettyService}. Use {@link JettyService#of(Server)} if you have a configured Jetty
 * {@link Server} instance.
 */
public final class JettyServiceBuilder extends AbstractJettyServiceBuilder {

    final ImmutableList.Builder<Handler.Singleton> handlerWrappers = ImmutableList.builder();
    private final ImmutableList.Builder<EventListener> eventListeners = ImmutableList.builder();

    JettyServiceBuilder() {}

    /**
     * Adds the specified event listener to the Jetty {@link Server}.
     */
    public JettyServiceBuilder eventListener(EventListener eventListener) {
        eventListeners.add(requireNonNull(eventListener, "eventListener"));
        return this;
    }

    @Override
    public JettyServiceBuilder hostname(String hostname) {
        return (JettyServiceBuilder) super.hostname(hostname);
    }

    @Override
    public JettyServiceBuilder attr(String name, Object attribute) {
        return (JettyServiceBuilder) super.attr(name, attribute);
    }

    @Override
    public JettyServiceBuilder bean(Object bean) {
        return (JettyServiceBuilder) super.bean(bean);
    }

    @Override
    public JettyServiceBuilder bean(Object bean, boolean managed) {
        return (JettyServiceBuilder) super.bean(bean, managed);
    }

    @Override
    public JettyServiceBuilder dumpAfterStart(boolean dumpAfterStart) {
        return (JettyServiceBuilder) super.dumpAfterStart(dumpAfterStart);
    }

    @Override
    public JettyServiceBuilder dumpBeforeStop(boolean dumpBeforeStop) {
        return (JettyServiceBuilder) super.dumpBeforeStop(dumpBeforeStop);
    }

    @Override
    public JettyServiceBuilder handler(Handler handler) {
        return (JettyServiceBuilder) super.handler(handler);
    }

    /**
     * Adds the specified {@link Handler.Wrapper} to the Jetty {@link Server}.
     *
     * @see Server#insertHandler(Singleton)
     */
    public JettyServiceBuilder insertHandler(Handler.Singleton handler) {
        handlerWrappers.add(requireNonNull(handler, "handler"));
        return this;
    }

    @Override
    public JettyServiceBuilder httpConfiguration(HttpConfiguration httpConfiguration) {
        return (JettyServiceBuilder) super.httpConfiguration(httpConfiguration);
    }

    @Override
    public JettyServiceBuilder requestLog(RequestLog requestLog) {
        return (JettyServiceBuilder) super.requestLog(requestLog);
    }

    @Override
    public JettyServiceBuilder stopTimeoutMillis(long stopTimeoutMillis) {
        return (JettyServiceBuilder) super.stopTimeoutMillis(stopTimeoutMillis);
    }

    @Override
    public JettyServiceBuilder customizer(Consumer<? super Server> customizer) {
        return (JettyServiceBuilder) super.customizer(customizer);
    }

    /**
     * Returns a newly-created {@link JettyService} based on the properties of this builder.
     */
    public JettyService build() {
        // Make a copy of the properties that's used in `serverFactory` so that any further modification of
        // this builder doesn't affect the built server.
        final Boolean dumpAfterStart = this.dumpAfterStart;
        final Boolean dumpBeforeStop = this.dumpBeforeStop;
        final Long stopTimeoutMillis = this.stopTimeoutMillis;
        final Handler handler = this.handler;
        final RequestLog requestLog = this.requestLog;
        final Map<String, Object> attrs = this.attrs.build();
        final List<Bean> beans = this.beans.build();
        final List<Handler.Singleton> handlerWrappers = this.handlerWrappers.build();
        final List<EventListener> eventListeners = this.eventListeners.build();
        final List<Consumer<? super Server>> customizers = this.customizers.build();

        final Function<BlockingTaskExecutor, Server> serverFactory = new Function<>() {

            private final ReentrantShortLock lock = new ReentrantShortLock();
            @Nullable
            private Server server;

            @Override
            public Server apply(BlockingTaskExecutor blockingTaskExecutor) {
                lock.lock();
                try {
                    if (server != null) {
                        return server;
                    }
                    server = new Server(new ArmeriaThreadPool(blockingTaskExecutor));

                    if (dumpAfterStart != null) {
                        server.setDumpAfterStart(dumpAfterStart);
                    }
                    if (dumpBeforeStop != null) {
                        server.setDumpBeforeStop(dumpBeforeStop);
                    }
                    if (stopTimeoutMillis != null) {
                        server.setStopTimeout(stopTimeoutMillis);
                    }

                    if (handler != null) {
                        server.setHandler(handler);
                    }
                    if (requestLog != null) {
                        server.setRequestLog(requestLog);
                    }

                    handlerWrappers.forEach(server::insertHandler);
                    attrs.forEach(server::setAttribute);
                    beans.forEach(bean -> {
                        final Boolean managed = bean.isManaged();
                        if (managed == null) {
                            server.addBean(bean.bean());
                        } else {
                            server.addBean(bean.bean(), managed);
                        }
                    });

                    eventListeners.forEach(server::addEventListener);

                    customizers.forEach(c -> c.accept(server));
                    return server;
                } finally {
                    lock.unlock();
                }
            }
        };

        final Consumer<Server> postStopTask = server -> {
            try {
                JettyService.logger.info("Destroying an embedded Jetty: {}", server);
                server.destroy();
            } catch (Exception e) {
                JettyService.logger.warn("Failed to destroy an embedded Jetty: {}", server, e);
            }
        };
        return new JettyService(hostname, serverFactory, postStopTask);
    }
}
