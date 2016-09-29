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
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.MessageLogConsumer;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;

/**
 * Decorates a {@link Client} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class LoggingClient<I extends Request, O extends Response> extends LogCollectingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(LoggingClient.class);

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at {@link LogLevel#INFO}.
     */
    public LoggingClient(Client<? super I, ? extends O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     */
    public LoggingClient(Client<? super I, ? extends O> delegate, LogLevel level) {
        super(delegate, new LoggingConsumer(logger, level));
    }

    private static final class LoggingConsumer implements MessageLogConsumer {

        private static final String REQUEST_FORMAT = "{} Request: {}";
        private static final String RESPONSE_FORMAT = "{} Response: {}";

        private final Logger logger;
        private final LogLevel level;

        LoggingConsumer(Logger logger, LogLevel level) {
            this.logger = requireNonNull(logger, "logger");
            this.level = requireNonNull(level, "level");
        }

        @Override
        public void onRequest(RequestContext ctx, RequestLog req) {
            level.log(logger, REQUEST_FORMAT, ctx, req);

        }

        @Override
        public void onResponse(RequestContext ctx, ResponseLog res) {
            level.log(logger, RESPONSE_FORMAT, ctx, res);
        }
    }
}
