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

package com.linecorp.armeria.server.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public class LoggingService<I extends Request, O extends Response> extends SimpleDecoratingService<I, O> {

    private static final String REQUEST_FORMAT = "Request: {}";
    private static final String RESPONSE_FORMAT = "Response: {}";

    /**
     * Returns a new {@link Service} decorator that logs {@link Request}s and {@link Response}s at
     * {@link LogLevel#INFO}.
     */
    public static <I extends Request, O extends Response>
    Function<Service<I, O>, LoggingService<I, O>> newDecorator() {
        return LoggingService::new;
    }

    /**
     * Returns a new {@link Service} decorator that logs {@link Request}s and {@link Response}s.
     *
     * @param level the log level
     */
    public static <I extends Request, O extends Response>
    Function<Service<I, O>, LoggingService<I, O>> newDecorator(LogLevel level) {
        return delegate -> new LoggingService<>(delegate, level);
    }

    private final LogLevel level;

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at {@link LogLevel#INFO}.
     */
    public LoggingService(Service<I, O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     */
    public LoggingService(Service<I, O> delegate, LogLevel level) {
        super(delegate);
        this.level = requireNonNull(level, "level");
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        ctx.log().addListener(this::logRequest, RequestLogAvailability.REQUEST_END);
        ctx.log().addListener(this::logResponse, RequestLogAvailability.COMPLETE);
        return delegate().serve(ctx, req);
    }

    /**
     * Logs a stringified request of {@link RequestLog}.
     */
    protected void logRequest(RequestLog log) {
        final Logger logger = ((ServiceRequestContext) log.context()).logger();
        if (level.isEnabled(logger)) {
            level.log(logger, REQUEST_FORMAT, log.toStringRequestOnly());
        }
    }

    /**
     * Logs a stringified response of {@link RequestLog}.
     */
    protected void logResponse(RequestLog log) {
        final Logger logger = ((ServiceRequestContext) log.context()).logger();
        if (level.isEnabled(logger)) {
            level.log(logger, RESPONSE_FORMAT, log.toStringResponseOnly());
        }
    }
}
