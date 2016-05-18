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

package com.linecorp.armeria.client.logging;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * Decorates a {@link Client} to log invocation requests and responses.
 */
public class LoggingClient extends DecoratingClient {

    private static final Logger logger = LoggerFactory.getLogger(LoggingClient.class);

    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";

    private final LogLevel level;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    public LoggingClient(Client delegate) {
        this(delegate, LogLevel.INFO);
    }

    public LoggingClient(Client delegate, LogLevel level) {
        super(delegate);
        this.level = requireNonNull(level, "level");
    }

    @Override
    public Response execute(ClientRequestContext ctx, Request req) throws Exception {
        ctx.awaitRequestLog()
           .thenAccept(log -> log(ctx, level, log))
           .exceptionally(CompletionActions::log);

        ctx.awaitResponseLog()
           .thenAccept(log -> log(ctx, level, log))
           .exceptionally(CompletionActions::log);

        return delegate().execute(ctx, req);
    }

    protected void log(ClientRequestContext ctx, LogLevel level, RequestLog log) {
        level.log(logger, REQUEST_FORMAT, ctx, log);
    }

    protected void log(ClientRequestContext ctx, LogLevel level, ResponseLog log) {
        level.log(logger, RESPONSE_FORMAT, ctx, log);
    }
}
