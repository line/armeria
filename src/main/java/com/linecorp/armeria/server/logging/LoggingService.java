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
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A decorator {@link Service} that logs all requests and responses.
 */
public class LoggingService extends DecoratingService {

    private static final String REQUEST_FORMAT = "Request: {}";
    private static final String RESPONSE_FORMAT = "Response: {}";

    private final LogLevel level;

    public LoggingService(Service delegate) {
        this(delegate, LogLevel.INFO);
    }

    public LoggingService(Service delegate, LogLevel level) {
        super(delegate);
        this.level = requireNonNull(level, "level");
    }

    @Override
    public Response serve(ServiceRequestContext ctx, Request req) throws Exception {
        ctx.awaitRequestLog().thenAccept(log -> log(ctx, level, log));
        ctx.awaitResponseLog().thenAccept(log -> log(ctx, level, log));
        return delegate().serve(ctx, req);
    }

    protected void log(ServiceRequestContext ctx, LogLevel level, RequestLog log) {
        level.log(ctx.logger(), REQUEST_FORMAT, log);
    }

    protected void log(ServiceRequestContext ctx, LogLevel level, ResponseLog log) {
        level.log(ctx.logger(), RESPONSE_FORMAT, log);
    }
}
