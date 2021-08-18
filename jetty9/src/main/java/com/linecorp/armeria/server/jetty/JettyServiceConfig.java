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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.component.Container.Listener;
import org.eclipse.jetty.util.component.LifeCycle;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class JettyServiceConfig {

    @Nullable
    private final String hostname;
    @Nullable
    private final Boolean dumpAfterStart;
    @Nullable
    private final Boolean dumpBeforeStop;
    @Nullable
    private final Long stopTimeoutMillis;
    @Nullable
    private final Handler handler;
    @Nullable
    private final RequestLog requestLog;
    @Nullable
    private final Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory;
    private final Map<String, Object> attrs;
    private final List<Bean> beans;
    private final List<HandlerWrapper> handlerWrappers;
    private final List<Listener> eventListeners;
    private final List<LifeCycle.Listener> lifeCycleListeners;
    private final List<Consumer<? super Server>> configurators;

    JettyServiceConfig(@Nullable String hostname,
                       @Nullable Boolean dumpAfterStart, @Nullable Boolean dumpBeforeStop,
                       @Nullable Long stopTimeoutMillis,
                       @Nullable Handler handler, @Nullable RequestLog requestLog,
                       @Nullable Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory,
                       Map<String, Object> attrs, List<Bean> beans, List<HandlerWrapper> handlerWrappers,
                       List<Listener> eventListeners, List<LifeCycle.Listener> lifeCycleListeners,
                       List<Consumer<? super Server>> configurators) {

        this.hostname = hostname;
        this.dumpAfterStart = dumpAfterStart;
        this.dumpBeforeStop = dumpBeforeStop;
        this.stopTimeoutMillis = stopTimeoutMillis;
        this.handler = handler;
        this.requestLog = requestLog;
        this.sessionIdManagerFactory = sessionIdManagerFactory;
        this.attrs = Collections.unmodifiableMap(attrs);
        this.beans = Collections.unmodifiableList(beans);
        this.handlerWrappers = Collections.unmodifiableList(handlerWrappers);
        this.eventListeners = Collections.unmodifiableList(eventListeners);
        this.lifeCycleListeners = Collections.unmodifiableList(lifeCycleListeners);
        this.configurators = Collections.unmodifiableList(configurators);
    }

    @Nullable
    String hostname() {
        return hostname;
    }

    @Nullable
    Boolean dumpAfterStart() {
        return dumpAfterStart;
    }

    @Nullable
    Boolean dumpBeforeStop() {
        return dumpBeforeStop;
    }

    @Nullable
    Long stopTimeoutMillis() {
        return stopTimeoutMillis;
    }

    @Nullable
    Handler handler() {
        return handler;
    }

    @Nullable
    RequestLog requestLog() {
        return requestLog;
    }

    @Nullable
    Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory() {
        return sessionIdManagerFactory;
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
                sessionIdManagerFactory, attrs, beans, handlerWrappers, eventListeners, lifeCycleListeners,
                configurators);
    }

    static String toString(
            Object holder, @Nullable String hostname, @Nullable Boolean dumpAfterStart,
            @Nullable Boolean dumpBeforeStop, @Nullable Long stopTimeout,
            @Nullable Handler handler, @Nullable RequestLog requestLog,
            @Nullable Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory,
            Map<String, Object> attrs, List<Bean> beans, List<HandlerWrapper> handlerWrappers,
            List<Listener> eventListeners, List<LifeCycle.Listener> lifeCycleListeners,
            List<Consumer<? super Server>> configurators) {

        return MoreObjects.toStringHelper(holder)
                          .add("hostname", hostname)
                          .add("dumpAfterStart", dumpAfterStart)
                          .add("dumpBeforeStop", dumpBeforeStop)
                          .add("stopTimeoutMillis", stopTimeout)
                          .add("handler", handler)
                          .add("requestLog", requestLog)
                          .add("sessionIdManagerFactory", sessionIdManagerFactory)
                          .add("attrs", attrs)
                          .add("beans", beans)
                          .add("handlerWrappers", handlerWrappers)
                          .add("eventListeners", eventListeners)
                          .add("lifeCycleListeners", lifeCycleListeners)
                          .add("configurators", configurators)
                          .toString();
    }

    static final class Bean {

        private final Object bean;
        @Nullable
        private final Boolean managed;

        Bean(Object bean, @Nullable Boolean managed) {
            this.bean = requireNonNull(bean, "bean");
            this.managed = managed;
        }

        Object bean() {
            return bean;
        }

        @Nullable
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
