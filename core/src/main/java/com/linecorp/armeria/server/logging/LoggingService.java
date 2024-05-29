/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.internal.common.logging.LoggingUtils.log;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to log {@link HttpRequest}s and {@link HttpResponse}s.
 */
public final class LoggingService extends SimpleDecoratingHttpService {

    private static final Logger logger = LoggerFactory.getLogger(LoggingService.class);

    /**
     * Returns a new {@link HttpService} decorator that logs {@link HttpRequest}s and {@link HttpResponse}s at
     * {@link LogLevel#DEBUG} for success, {@link LogLevel#WARN} for failure. See {@link LoggingServiceBuilder}
     * for more information on the default settings.
     */
    public static Function<? super HttpService, LoggingService> newDecorator() {
        return builder().newDecorator();
    }

    /**
     * Returns a newly created {@link LoggingServiceBuilder}.
     */
    public static LoggingServiceBuilder builder() {
        return new LoggingServiceBuilder().defaultLogger(logger);
    }

    private final LogWriter logWriter;
    private final Sampler<? super RequestLog> sampler;

    LoggingService(HttpService delegate, LogWriter logWriter,
                   Sampler<? super ServiceRequestContext> successSampler,
                   Sampler<? super ServiceRequestContext> failureSampler) {
        super(requireNonNull(delegate, "delegate"));
        this.logWriter = requireNonNull(logWriter, "logWriter");
        requireNonNull(successSampler, "successSampler");
        requireNonNull(failureSampler, "failureSampler");
        sampler = requestLog -> {
            final ServiceRequestContext ctx = (ServiceRequestContext) requestLog.context();
            if (ctx.config().successFunction().isSuccess(ctx, requestLog)) {
                return successSampler.isSampled(ctx);
            }
            return failureSampler.isSampled(ctx);
        };
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        ctx.setShouldReportUnloggedExceptions(false);
        ctx.log().whenComplete().thenAccept(requestLog -> {
            if (sampler.isSampled(requestLog)) {
                log(ctx, requestLog, logWriter);
            }
        });
        return unwrap().serve(ctx, req);
    }
}
