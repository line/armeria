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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;

/**
 * Builds a {@link JettyService}. Use {@link JettyService#of(Server)} if you have a configured Jetty
 * {@link Server} instance.
 */
public final class JettyServiceBuilder extends AbstractJettyServiceBuilder {

    private final ImmutableList.Builder<Container.Listener> eventListeners = ImmutableList.builder();
    private final ImmutableList.Builder<LifeCycle.Listener> lifeCycleListeners = ImmutableList.builder();
    private final ImmutableList.Builder<HandlerWrapper> handlerWrappers = ImmutableList.builder();

    @Nullable
    private Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory;
    private boolean tlsReverseDnsLookup;

    JettyServiceBuilder() {}

    /**
     * Adds the specified event listener to the Jetty {@link Server}.
     */
    public JettyServiceBuilder eventListener(Container.Listener eventListener) {
        eventListeners.add(requireNonNull(eventListener, "eventListener"));
        return this;
    }

    /**
     * Adds the specified life cycle listener to the Jetty {@link Server}.
     */
    public JettyServiceBuilder lifeCycleListener(LifeCycle.Listener lifeCycleListener) {
        lifeCycleListeners.add(requireNonNull(lifeCycleListener, "lifeCycleListener"));
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
     * Adds the specified {@link HandlerWrapper} to the Jetty {@link Server}.
     *
     * @see Server#insertHandler(HandlerWrapper)
     */
    public JettyServiceBuilder handlerWrapper(HandlerWrapper handlerWrapper) {
        handlerWrappers.add(requireNonNull(handlerWrapper, "handlerWrapper"));
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

    /**
     * Sets the {@link SessionIdManager} of the Jetty {@link Server}. This method is a shortcut for:
     * <pre>{@code
     * sessionIdManagerFactory(server -> sessionIdManager);
     * }</pre>
     *
     * @see Server#setSessionIdManager(SessionIdManager)
     */
    public JettyServiceBuilder sessionIdManager(SessionIdManager sessionIdManager) {
        requireNonNull(sessionIdManager, "sessionIdManager");
        return sessionIdManagerFactory(server -> sessionIdManager);
    }

    /**
     * Sets the factory that creates a new instance of {@link SessionIdManager} for the Jetty {@link Server}.
     *
     * @see Server#setSessionIdManager(SessionIdManager)
     */
    public JettyServiceBuilder sessionIdManagerFactory(
            Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory) {
        requireNonNull(sessionIdManagerFactory, "sessionIdManagerFactory");
        this.sessionIdManagerFactory = sessionIdManagerFactory;
        return this;
    }

    @Override
    public JettyServiceBuilder stopTimeoutMillis(long stopTimeoutMillis) {
        return (JettyServiceBuilder) super.stopTimeoutMillis(stopTimeoutMillis);
    }

    /**
     * Sets whether Jetty has to perform reverse DNS lookup for the remote IP address on a TLS connection.
     * By default, this flag is disabled because it is known to cause performance issues when the DNS server
     * is not responsive enough. However, you might want to take the risk and enable it if you want the same
     * behavior with Jetty 9.3 when mTLS is enabled.
     *
     * @see <a href="https://github.com/eclipse/jetty.project/issues/1235">Jetty issue #1235</a>
     * @see <a href="https://github.com/eclipse/jetty.project/commit/de7c146bd741307cd924a9dcef386d516e75b1e9">Jetty commit de7c146</a>
     */
    public JettyServiceBuilder tlsReverseDnsLookup(boolean tlsReverseDnsLookup) {
        this.tlsReverseDnsLookup = tlsReverseDnsLookup;
        return this;
    }

    @Override
    public JettyServiceBuilder customizer(Consumer<? super Server> customizer) {
        return (JettyServiceBuilder) super.customizer(customizer);
    }

    @Override
    public JettyServiceBuilder configurator(Consumer<? super Server> configurator) {
        return (JettyServiceBuilder) super.configurator(configurator);
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
        final Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory =
                this.sessionIdManagerFactory;
        final Map<String, Object> attrs = this.attrs.build();
        final List<Bean> beans = this.beans.build();
        final List<HandlerWrapper> handlerWrappers = this.handlerWrappers.build();
        final List<Container.Listener> eventListeners = this.eventListeners.build();
        final List<LifeCycle.Listener> lifeCycleListeners = this.lifeCycleListeners.build();
        final List<Consumer<? super Server>> customizers = this.customizers.build();

        final Function<BlockingTaskExecutor, Server> serverFactory = blockingTaskExecutor -> {
            final Server server = new Server(new ArmeriaThreadPool(blockingTaskExecutor));

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
            if (sessionIdManagerFactory != null) {
                server.setSessionIdManager(sessionIdManagerFactory.apply(server));
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
            lifeCycleListeners.forEach(server::addLifeCycleListener);

            customizers.forEach(c -> c.accept(server));

            return server;
        };

        final Consumer<Server> postStopTask = server -> {
            try {
                JettyService.logger.info("Destroying an embedded Jetty: {}", server);
                server.destroy();
            } catch (Exception e) {
                JettyService.logger.warn("Failed to destroy an embedded Jetty: {}", server, e);
            }
        };

        return new JettyService(hostname, tlsReverseDnsLookup, serverFactory, postStopTask);
    }
}
