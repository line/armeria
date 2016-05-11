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

package com.linecorp.armeria.server.http.jetty;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;

import com.linecorp.armeria.server.http.jetty.JettyServiceConfig.Bean;

/**
 * Builds a {@link JettyService}. Use {@link JettyService#forServer(String, Server)} if you have a configured
 * Jetty {@link Server} instance.
 */
public final class JettyServiceBuilder {

    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final List<Bean> beans = new ArrayList<>();
    private final List<Container.Listener> eventListeners = new ArrayList<>();
    private final List<LifeCycle.Listener> lifeCycleListeners = new ArrayList<>();
    private final List<Consumer<? super Server>> configurators = new ArrayList<>();

    private String hostname = "localhost";
    private Boolean dumpAfterStart;
    private Boolean dumpBeforeStop;
    private Handler handler;
    private RequestLog requestLog;
    private SessionIdManager sessionIdManager;
    private Long stopTimeoutMillis;

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
     * Sets the {@link RequestLog} of the Jetty {@link Server}.
     *
     * @see Server#setRequestLog(RequestLog)
     */
    public JettyServiceBuilder requestLog(RequestLog requestLog) {
        this.requestLog = requireNonNull(requestLog, "requestLog");
        return this;
    }

    /**
     * Sets the {@link SessionIdManager} of the Jetty {@link Server}.
     *
     * @see Server#setSessionIdManager(SessionIdManager)
     */
    public JettyServiceBuilder sessionIdManager(SessionIdManager sessionIdManager) {
        this.sessionIdManager = requireNonNull(sessionIdManager, "sessionIdManager");
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
     * Creates a new {@link JettyService}.
     */
    public JettyService build() {
        return JettyService.forConfig(new JettyServiceConfig(
                hostname, dumpAfterStart, dumpBeforeStop, stopTimeoutMillis, handler, requestLog,
                sessionIdManager, attrs, beans, eventListeners, lifeCycleListeners, configurators));
    }

    @Override
    public String toString() {
        return JettyServiceConfig.toString(
                this, hostname, dumpAfterStart, dumpBeforeStop, stopTimeoutMillis, handler,
                requestLog, sessionIdManager, attrs, beans, eventListeners, lifeCycleListeners, configurators);
    }
}
