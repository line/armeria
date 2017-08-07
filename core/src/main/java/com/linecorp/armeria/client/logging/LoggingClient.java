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

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;

/**
 * Decorates a {@link Client} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public final class LoggingClient<I extends Request, O extends Response> extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(LoggingClient.class);

    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";

    /**
     * Returns a new {@link Client} decorator that logs {@link Request}s and {@link Response}s at
     * {@link LogLevel#INFO}.
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, LoggingClient<I, O>> newDecorator() {
        return LoggingClient::new;
    }

    /**
     * Returns a new {@link Client} decorator that logs {@link Request}s and {@link Response}s.
     *
     * @param level the log level
     */
    public static <I extends Request, O extends Response>
    Function<Client<I, O>, LoggingClient<I, O>> newDecorator(LogLevel level) {
        return delegate -> new LoggingClient<>(delegate, level);
    }

    private final LogLevel level;

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at {@link LogLevel#INFO}.
     */
    public LoggingClient(Client<I, O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     */
    public LoggingClient(Client<I, O> delegate, LogLevel level) {
        super(delegate);
        this.level = requireNonNull(level, "level");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        ctx.log().addListener(this::logRequest, RequestLogAvailability.REQUEST_END);
        ctx.log().addListener(this::logResponse, RequestLogAvailability.COMPLETE);
        return delegate().execute(ctx, req);
    }

    private void logRequest(RequestLog log) {
        if (level.isEnabled(logger)) {
            level.log(logger, REQUEST_FORMAT, log.context(), log.toStringRequestOnly());
        }
    }

    private void logResponse(RequestLog log) {
        if (level.isEnabled(logger)) {
            level.log(logger, RESPONSE_FORMAT, log.context(), log.toStringResponseOnly());
        }
    }
}
