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
import com.linecorp.armeria.common.util.Exceptions;

final class ExceptionReportingServiceErrorHandler implements ServiceErrorHandler {

    private final ServiceErrorHandler delegate;
    private final UnloggedExceptionsReporter reporter;

    ExceptionReportingServiceErrorHandler(ServiceErrorHandler delegate, UnloggedExceptionsReporter reporter) {
        this.delegate = delegate;
        this.reporter = reporter;
    }

    @Nullable
    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        final HttpResponse httpResponse = delegate.onServiceException(ctx, cause);
        if (ctx.shouldReportUnloggedExceptions() && !isIgnorableException(cause)) {
            reporter.report(cause);
        }
        return httpResponse;
    }

    private static boolean isIgnorableException(Throwable cause) {
        if (Exceptions.isExpected(cause)) {
            return true;
        }
        return (cause instanceof HttpStatusException || cause instanceof HttpResponseException) &&
               cause.getCause() == null;
    }

    @Nullable
    @Override
    public AggregatedHttpResponse renderStatus(ServiceRequestContext ctx, RequestHeaders headers,
                                               HttpStatus status, @Nullable String description,
                                               @Nullable Throwable cause) {
        return delegate.renderStatus(ctx, headers, status, description, cause);
    }
}
