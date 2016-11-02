/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.MessageLogConsumer;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.logging.LogCollectingService;

/**
 * A decorating service which provides support of structured and optionally externalized request/response
 * content logging.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 * @param <L> the type of the structured log representation
 */
public abstract class StructuredLoggingService<I extends Request, O extends Response, L>
        extends LogCollectingService<I, O> {

    private Server associatedServer;

    /**
     * Creates a new {@link StructuredLoggingService}.
     *
     * @param delegate the {@link Service} being decorated
     * @param logBuilder an instance of {@link StructuredLogBuilder} which is used to construct an entry of
     *        structured log
     */
    protected StructuredLoggingService(Service<? super I, ? extends O> delegate,
                                       StructuredLogBuilder<L> logBuilder) {
        super(delegate);
        init(new MessageLogConsumer() {
            @Override
            public void onRequest(RequestContext ctx, RequestLog req) throws Exception { /* noop */ }

            @Override
            public void onResponse(RequestContext ctx, ResponseLog res) throws Exception {
                L structuredLog = logBuilder.build(ctx, res);
                if (structuredLog != null) {
                    writeLog(ctx, res, structuredLog);
                }
            }
        });
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
            public void serverStopped(Server server) throws Exception {
                close();
            }
        });
    }

    /**
     * Writes given {@code structuredLog} to the underlying system.
     *
     * @param reqCtx the request context of which a {@code structuredLog} being written
     * @param resLog the response structuredLog which is a source of constructed {@code structuredLog}
     * @param structuredLog the content of a structuredLog
     */
    protected abstract void writeLog(RequestContext reqCtx, ResponseLog resLog, L structuredLog);

    /**
     * Cleanup resources which were opened for logging.
     */
    protected void close() {
        // noop by default
    }
}
