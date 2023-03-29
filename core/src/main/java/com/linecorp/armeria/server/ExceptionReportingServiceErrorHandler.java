/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

final class ExceptionReportingServiceErrorHandler implements ServiceErrorHandler {

    private final ServiceErrorHandler delegate;
    private final UnhandledExceptionsReporter reporter;

    ExceptionReportingServiceErrorHandler(ServiceErrorHandler delegate, UnhandledExceptionsReporter reporter) {
        this.delegate = delegate;
        this.reporter = reporter;
    }

    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        if (ctx.shouldReportUnhandledExceptions() && !isIgnorableException(cause)) {
            reporter.report(cause);
        }
        return delegate.onServiceException(ctx, cause);
    }

    private static boolean isIgnorableException(Throwable cause) {
        return (cause instanceof HttpStatusException || cause instanceof HttpResponseException) &&
               cause.getCause() == null;
    }

    @Nullable
    @Override
    public AggregatedHttpResponse renderStatus(ServiceConfig config, @Nullable RequestHeaders headers,
                                               HttpStatus status, @Nullable String description,
                                               @Nullable Throwable cause) {
        return delegate.renderStatus(config, headers, status, description, cause);
    }
}
