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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.component.Container.Listener;
import org.eclipse.jetty.util.component.LifeCycle;

final class JettyServiceConfig {

    private final String hostname;
    private final Boolean dumpAfterStart;
    private final Boolean dumpBeforeStop;
    private final Long stopTimeoutMillis;
    private final Handler handler;
    private final RequestLog requestLog;
    private final SessionIdManager sessionIdManager;
    private final Map<String, Object> attrs;
    private final List<Bean> beans;
    private final List<HandlerWrapper> handlerWrappers;
    private final List<Listener> eventListeners;
    private final List<LifeCycle.Listener> lifeCycleListeners;
    private final List<Consumer<? super Server>> configurators;

    JettyServiceConfig(String hostname,
                       Boolean dumpAfterStart, Boolean dumpBeforeStop, Long stopTimeoutMillis,
                       Handler handler, RequestLog requestLog, SessionIdManager sessionIdManager,
                       Map<String, Object> attrs, List<Bean> beans, List<HandlerWrapper> handlerWrappers,
                       List<Listener> eventListeners, List<LifeCycle.Listener> lifeCycleListeners,
                       List<Consumer<? super Server>> configurators) {

        this.hostname = hostname;
        this.dumpAfterStart = dumpAfterStart;
        this.dumpBeforeStop = dumpBeforeStop;
        this.stopTimeoutMillis = stopTimeoutMillis;
        this.handler = handler;
        this.requestLog = requestLog;
        this.sessionIdManager = sessionIdManager;
        this.attrs = Collections.unmodifiableMap(attrs);
        this.beans = Collections.unmodifiableList(beans);
        this.handlerWrappers = Collections.unmodifiableList(handlerWrappers);
        this.eventListeners = Collections.unmodifiableList(eventListeners);
        this.lifeCycleListeners = Collections.unmodifiableList(lifeCycleListeners);
        this.configurators = Collections.unmodifiableList(configurators);
    }

    Optional<String> hostname() {
        return Optional.ofNullable(hostname);
    }

    Optional<Boolean> dumpAfterStart() {
        return Optional.ofNullable(dumpAfterStart);
    }

    Optional<Boolean> dumpBeforeStop() {
        return Optional.ofNullable(dumpBeforeStop);
    }

    Optional<Long> stopTimeoutMillis() {
        return Optional.ofNullable(stopTimeoutMillis);
    }

    Optional<Handler> handler() {
        return Optional.ofNullable(handler);
    }

    Optional<RequestLog> requestLog() {
        return Optional.ofNullable(requestLog);
    }

    Optional<SessionIdManager> sessionIdManager() {
        return Optional.ofNullable(sessionIdManager);
    }

    Map<String, Object> attrs() {
        return attrs;
    }

    List<Bean> beans() {
        return beans;
    }

    List<HandlerWrapper> handlerWrappers() {
        return handlerWrappers;
    }

    List<Listener> eventListeners() {
        return eventListeners;
    }

    List<LifeCycle.Listener> lifeCycleListeners() {
        return lifeCycleListeners;
    }

    List<Consumer<? super Server>> configurators() {
        return configurators;
    }

    @Override
    public String toString() {
        return toString(
                this, hostname, dumpAfterStart, dumpBeforeStop, stopTimeoutMillis, handler, requestLog,
                sessionIdManager, attrs, beans, handlerWrappers, eventListeners, lifeCycleListeners,
                configurators);
    }

    static String toString(
            Object holder, String hostname, Boolean dumpAfterStart, Boolean dumpBeforeStop, Long stopTimeout,
            Handler handler, RequestLog requestLog, SessionIdManager sessionIdManager,
            Map<String, Object> attrs, List<Bean> beans, List<HandlerWrapper> handlerWrappers,
            List<Listener> eventListeners, List<LifeCycle.Listener> lifeCycleListeners,
            List<Consumer<? super Server>> configurators) {

        final StringBuilder buf = new StringBuilder(256);
        buf.append(holder.getClass().getSimpleName());
        buf.append("(hostname: ");
        buf.append(hostname);
        buf.append(", dumpAfterStart: ");
        buf.append(dumpAfterStart);
        buf.append(", dumpBeforeStop: ");
        buf.append(dumpBeforeStop);
        buf.append(", stopTimeoutMillis: ");
        buf.append(stopTimeout);
        buf.append(", handler: ");
        buf.append(handler);
        buf.append(", requestLog: ");
        buf.append(requestLog);
        buf.append(", sessionIdManager: ");
        buf.append(sessionIdManager);
        buf.append(", attrs: ");
        buf.append(attrs);
        buf.append(", beans: ");
        buf.append(beans);
        buf.append(", handlerWrappers: ");
        buf.append(handlerWrappers);
        buf.append(", eventListeners: ");
        buf.append(eventListeners);
        buf.append(", lifeCycleListeners: ");
        buf.append(lifeCycleListeners);
        buf.append(", configurators: ");
        buf.append(configurators);
        buf.append(')');
        return buf.toString();
    }

    static final class Bean {

        private final Object bean;
        private final Boolean managed;

        Bean(Object bean, Boolean managed) {
            this.bean = requireNonNull(bean, "bean");
            this.managed = managed;
        }

        Object bean() {
            return bean;
        }

        Boolean isManaged() {
            return managed;
        }

        @Override
        public String toString() {
            final String mode = managed != null ? managed ? "managed" : "unmanaged"
                                                : "auto";
            return "(" + bean + ", " + mode + ')';
        }
    }
}
