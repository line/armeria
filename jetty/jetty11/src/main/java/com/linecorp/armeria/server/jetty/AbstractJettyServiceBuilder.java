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

import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.HandlerWrapper;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A skeletal builder implementation for {@link JettyServiceBuilder} in Jetty 9 and Jetty 10+ modules.
 */
public abstract class AbstractJettyServiceBuilder {

    final ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
    final ImmutableList.Builder<Bean> beans = ImmutableList.builder();
    final ImmutableList.Builder<HandlerWrapper> handlerWrappers = ImmutableList.builder();
    final ImmutableList.Builder<Consumer<? super Server>> customizers = ImmutableList.builder();

    @Nullable
    String hostname;
    @Nullable
    Boolean dumpAfterStart;
    @Nullable
    Boolean dumpBeforeStop;
    @Nullable
    Handler handler;
    @Nullable
    RequestLog requestLog;
    @Nullable
    Function<? super Server, ? extends SessionIdManager> sessionIdManagerFactory;
    @Nullable
    Long stopTimeoutMillis;
    boolean tlsReverseDnsLookup;

    AbstractJettyServiceBuilder() {}

    /**
     * Sets the default hostname of the Jetty {@link Server}.
     */
    public AbstractJettyServiceBuilder hostname(String hostname) {
        this.hostname = requireNonNull(hostname, "hostname");
        return this;
    }

    /**
     * Puts the specified attribute into the Jetty {@link Server}.
     *
     * @see Server#setAttribute(String, Object)
     */
    public AbstractJettyServiceBuilder attr(String name, Object attribute) {
        attrs.put(requireNonNull(name, "name"), requireNonNull(attribute, "attribute"));
        return this;
    }

    /**
     * Adds the specified bean to the Jetty {@link Server}.
     *
     * @see Server#addBean(Object)
     */
    public AbstractJettyServiceBuilder bean(Object bean) {
        beans.add(new Bean(bean, null));
        return this;
    }

    /**
     * Adds the specified bean to the Jetty {@link Server}.
     *
     * @see Server#addBean(Object, boolean)
     */
    public AbstractJettyServiceBuilder bean(Object bean, boolean managed) {
        beans.add(new Bean(bean, managed));
        return this;
    }

    /**
     * Sets whether the Jetty {@link Server} needs to dump its configuration after it started up.
     *
     * @see Server#setDumpAfterStart(boolean)
     */
    public AbstractJettyServiceBuilder dumpAfterStart(boolean dumpAfterStart) {
        this.dumpAfterStart = dumpAfterStart;
        return this;
    }

    /**
     * Sets whether the Jetty {@link Server} needs to dump its configuration before it shuts down.
     *
     * @see Server#setDumpBeforeStop(boolean)
     */
    public AbstractJettyServiceBuilder dumpBeforeStop(boolean dumpBeforeStop) {
        this.dumpBeforeStop = dumpBeforeStop;
        return this;
    }

    /**
     * Sets the {@link Handler} of the Jetty {@link Server}.
     *
     * @see Server#setHandler(Handler)
     */
    public AbstractJettyServiceBuilder handler(Handler handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Adds the specified {@link HandlerWrapper} to the Jetty {@link Server}.
     *
     * @see Server#insertHandler(HandlerWrapper)
     */
    public AbstractJettyServiceBuilder handlerWrapper(HandlerWrapper handlerWrapper) {
        handlerWrappers.add(requireNonNull(handlerWrapper, "handlerWrapper"));
        return this;
    }

    /**
     * Adds the specified {@link HttpConfiguration} to the Jetty {@link Server}.
     * This method is a type-safe alias of {@link #bean(Object)}.
     */
    public AbstractJettyServiceBuilder httpConfiguration(HttpConfiguration httpConfiguration) {
        return bean(httpConfiguration);
    }

    /**
     * Sets the {@link RequestLog} of the Jetty {@link Server}.
     *
     * @see Server#setRequestLog(RequestLog)
     */
    public AbstractJettyServiceBuilder requestLog(RequestLog requestLog) {
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
    public AbstractJettyServiceBuilder sessionIdManager(SessionIdManager sessionIdManager) {
        requireNonNull(sessionIdManager, "sessionIdManager");
        return sessionIdManagerFactory(server -> sessionIdManager);
    }

    /**
     * Sets the factory that creates a new instance of {@link SessionIdManager} for the Jetty {@link Server}.
     *
     * @see Server#setSessionIdManager(SessionIdManager)
     */
    public AbstractJettyServiceBuilder sessionIdManagerFactory(
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
    public AbstractJettyServiceBuilder stopTimeoutMillis(long stopTimeoutMillis) {
        this.stopTimeoutMillis = stopTimeoutMillis;
        return this;
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
    public AbstractJettyServiceBuilder tlsReverseDnsLookup(boolean tlsReverseDnsLookup) {
        this.tlsReverseDnsLookup = tlsReverseDnsLookup;
        return this;
    }

    /**
     * Adds a {@link Consumer} that performs additional configuration operations against
     * the Jetty {@link Server} created by a {@link JettyService}.
     */
    public AbstractJettyServiceBuilder customizer(Consumer<? super Server> customizer) {
        customizers.add(requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * Adds a {@link Consumer} that performs additional configuration operations against
     * the Jetty {@link Server} created by a {@link JettyService}.
     *
     * @deprecated Use {@link #customizer(Consumer)}.
     */
    @Deprecated
    public AbstractJettyServiceBuilder configurator(Consumer<? super Server> configurator) {
        return customizer(requireNonNull(configurator, "configurator"));
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
