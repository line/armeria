/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * A decorating service which provides support of structured and optionally externalized request/response
 * content logging.
 *
 * @param <L> the type of the structured log representation
 */
public abstract class StructuredLoggingService<L> extends SimpleDecoratingHttpService {

    private final StructuredLogBuilder<L> logBuilder;
    @Nullable
    private Server associatedServer;

    /**
     * Creates a new {@link StructuredLoggingService}.
     *
     * @param delegate the {@link HttpService} being decorated
     * @param logBuilder an instance of {@link StructuredLogBuilder} which is used to construct an entry of
     *        structured log
     */
    protected StructuredLoggingService(HttpService delegate, StructuredLogBuilder<L> logBuilder) {
        super(delegate);
        this.logBuilder = requireNonNull(logBuilder, "logBuilder");
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);

        if (associatedServer != null) {
            if (associatedServer != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        associatedServer = cfg.server();
        associatedServer.addListener(new ServerListenerAdapter() {
            @Override
            public void serverStopped(Server server) {
                close();
            }
        });
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        ctx.log().completeFuture().thenAccept(log -> {
            final L structuredLog = logBuilder.build(log);
            if (structuredLog != null) {
                writeLog(log, structuredLog);
            }
        });
        return delegate().serve(ctx, req);
    }

    /**
     * Writes given {@code structuredLog} to the underlying system.
     *  @param log the {@link RequestLog} which is a source of constructed {@code structuredLog}
     * @param structuredLog the content of a structuredLog
     */
    protected abstract void writeLog(RequestLog log, L structuredLog);

    /**
     * Cleanup resources which were opened for logging.
     */
    protected void close() {
        // noop by default
    }
}
