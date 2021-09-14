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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.jetty.JettyServiceConfig.Bean;

/**
 * Builds a {@link JettyService}. Use {@link JettyService#of(Server)} if you have a configured Jetty
 * {@link Server} instance.
 */
public final class JettyServiceBuilder {

    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final List<Bean> beans = new ArrayList<>();
    private final List<HandlerWrapper> handlerWrappers = new ArrayList<>();
    private final List<Container.Listener> eventListeners = new ArrayList<>();
    private final List<LifeCycle.Listener> lifeCycleListeners = new ArrayList<>();
    private final List<Consumer<? super Server>> configurators = new ArrayList<>();

    @Nullable
    private String hostname;
    @Nullable
    private Boolean dumpAfterStart;
    @Nullable
    private Boolean dumpBeforeStop;
    @Nullable
    private Handler handler;
    @Nullable
    private RequestLog requestLog;
    @Nullable
    private Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory;
    @Nullable
    private Long stopTimeoutMillis;

    JettyServiceBuilder() {}

    /**
     * Sets the default hostname of the Jetty {@link Server}.
     */
    public JettyServiceBuilder hostname(String hostname) {
        this.hostname = requireNonNull(hostname, "hostname");
        return this;
    }

    /**
     * Puts the specified attribute into the Jetty {@link Server}.
     *
     * @see Server#setAttribute(String, Object)
     */
    public JettyServiceBuilder attr(String name, Object attribute) {
        attrs.put(requireNonNull(name, "name"), requireNonNull(attribute, "attribute"));
        return this;
    }

    /**
     * Adds the specified bean to the Jetty {@link Server}.
     *
     * @see Server#addBean(Object)
     */
    public JettyServiceBuilder bean(Object bean) {
        beans.add(new Bean(bean, null));
        return this;
    }

    /**
     * Adds the specified bean to the Jetty {@link Server}.
     *
     * @see Server#addBean(Object, boolean)
     */
    public JettyServiceBuilder bean(Object bean, boolean managed) {
        beans.add(new Bean(bean, managed));
        return this;
    }

    /**
     * Sets whether the Jetty {@link Server} needs to dump its configuration after it started up.
     *
     * @see Server#setDumpAfterStart(boolean)
     */
    public JettyServiceBuilder dumpAfterStart(boolean dumpAfterStart) {
        this.dumpAfterStart = dumpAfterStart;
        return this;
    }

    /**
     * Sets whether the Jetty {@link Server} needs to dump its configuration before it shuts down.
     *
     * @see Server#setDumpBeforeStop(boolean)
     */
    public JettyServiceBuilder dumpBeforeStop(boolean dumpBeforeStop) {
        this.dumpBeforeStop = dumpBeforeStop;
        return this;
    }

    /**
     * Sets the {@link Handler} of the Jetty {@link Server}.
     *
     * @see Server#setHandler(Handler)
     */
    public JettyServiceBuilder handler(Handler handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
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

    /**
     * Sets the {@link RequestLog} of the Jetty {@link Server}.
     *
     * @see Server#setRequestLog(RequestLog)
     */
    public JettyServiceBuilder requestLog(RequestLog requestLog) {
        this.requestLog = requireNonNull(requestLog, "requestLog");
        return this;
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

    /**
     * Sets the graceful stop time of the {@link Server#stop()} in milliseconds.
     *
     * @see Server#setStopTimeout(long)
     */
    public JettyServiceBuilder stopTimeoutMillis(long stopTimeoutMillis) {
        this.stopTimeoutMillis = stopTimeoutMillis;
        return this;
    }

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

    /**
     * Adds a {@link Consumer} that performs additional configuration operations against
     * the Jetty {@link Server} created by a {@link JettyService}.
     */
    public JettyServiceBuilder configurator(Consumer<? super Server> configurator) {
        configurators.add(requireNonNull(configurator, "configurator"));
        return this;
    }

    /**
     * Returns a newly-created {@link JettyService} based on the properties of this builder.
     */
    public JettyService build() {
        final JettyServiceConfig config = new JettyServiceConfig(
                hostname, dumpAfterStart, dumpBeforeStop, stopTimeoutMillis, handler, requestLog,
                sessionIdManagerFactory, attrs, beans, handlerWrappers, eventListeners, lifeCycleListeners,
                configurators);

        final Function<ScheduledExecutorService, Server> serverFactory = blockingTaskExecutor -> {
            final Server server = new Server(new ArmeriaThreadPool(blockingTaskExecutor));

            if (config.dumpAfterStart() != null) {
                server.setDumpAfterStart(config.dumpAfterStart());
            }
            if (config.dumpBeforeStop() != null) {
                server.setDumpBeforeStop(config.dumpBeforeStop());
            }
            if (config.stopTimeoutMillis() != null) {
                server.setStopTimeout(config.stopTimeoutMillis());
            }

            if (config.handler() != null) {
                server.setHandler(config.handler());
            }
            if (config.requestLog() != null) {
                server.setRequestLog(requestLog);
            }
            if (config.sessionIdManagerFactory() != null) {
                server.setSessionIdManager(config.sessionIdManagerFactory().apply(server));
            }

            config.handlerWrappers().forEach(server::insertHandler);
            config.attrs().forEach(server::setAttribute);
            config.beans().forEach(bean -> {
                final Boolean managed = bean.isManaged();
                if (managed == null) {
                    server.addBean(bean.bean());
                } else {
                    server.addBean(bean.bean(), managed);
                }
            });

            config.eventListeners().forEach(server::addEventListener);
            config.lifeCycleListeners().forEach(server::addLifeCycleListener);

            config.configurators().forEach(c -> c.accept(server));

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

        return new JettyService(config.hostname(), serverFactory, postStopTask);
    }

    @Override
    public String toString() {
        return JettyServiceConfig.toString(
                this, hostname, dumpAfterStart, dumpBeforeStop, stopTimeoutMillis, handler, requestLog,
                sessionIdManagerFactory, attrs, beans, handlerWrappers, eventListeners, lifeCycleListeners,
                configurators);
    }
}
