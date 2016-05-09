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

import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;

import com.linecorp.armeria.server.http.HttpService;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="http://www.eclipse.org/jetty/">Jetty</a>.
 *
 * @see JettyServiceBuilder
 */
public class JettyService extends HttpService {

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param hostname the default hostname
     * @param jettyServer the Jetty {@link Server}
     */
    public static JettyService forServer(String hostname, Server jettyServer) {
        requireNonNull(hostname, "hostname");
        requireNonNull(jettyServer, "jettyServer");
        return new JettyService(hostname, blockingTaskExecutor -> jettyServer);
    }

    static JettyService forConfig(JettyServiceConfig config) {
        final Function<ExecutorService, Server> serverFactory = blockingTaskExecutor -> {
            final Server server = new Server(new ExecutorThreadPool(blockingTaskExecutor));

            config.dumpAfterStart().ifPresent(server::setDumpAfterStart);
            config.dumpBeforeStop().ifPresent(server::setDumpBeforeStop);
            config.handler().ifPresent(server::setHandler);
            config.requestLog().ifPresent(server::setRequestLog);
            config.sessionIdManager().ifPresent(server::setSessionIdManager);
            config.stopTimeoutMillis().ifPresent(server::setStopTimeout);

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

        return new JettyService(config.hostname(), serverFactory);
    }

    private JettyService(String hostname, Function<ExecutorService, Server> serverSupplier) {
        super(new JettyServiceInvocationHandler(hostname, serverSupplier));
    }
}
