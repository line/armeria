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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.MessageLogConsumer;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates a {@link Service} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public class LoggingService<I extends Request, O extends Response> extends LogCollectingService<I, O> {

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at {@link LogLevel#INFO}.
     */
    public LoggingService(Service<? super I, ? extends O> delegate) {
        this(delegate, LogLevel.INFO);
    }

    /**
     * Creates a new instance that logs {@link Request}s and {@link Response}s at the specified
     * {@link LogLevel}.
     */
    public LoggingService(Service<? super I, ? extends O> delegate, LogLevel level) {
        super(delegate, new LoggingConsumer(level));
    }

    private static final class LoggingConsumer implements MessageLogConsumer {

        private static final String REQUEST_FORMAT = "Request: {}";
        private static final String RESPONSE_FORMAT = "Response: {}";

        private final LogLevel level;

        LoggingConsumer(LogLevel level) {
            this.level = requireNonNull(level, "level");
        }

        @Override
        public void onRequest(RequestContext ctx, RequestLog req) {
            level.log(((ServiceRequestContext) ctx).logger(), REQUEST_FORMAT, req);
        }

        @Override
        public void onResponse(RequestContext ctx, ResponseLog res) {
            level.log(((ServiceRequestContext) ctx).logger(), RESPONSE_FORMAT, res);
        }
    }
}
