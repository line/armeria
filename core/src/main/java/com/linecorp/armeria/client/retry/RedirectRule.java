/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;

enum RedirectRule implements RetryRule {

    INSTANCE;

    @Override
    public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
        return ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenApply(log -> {
            final ResponseHeaders responseHeaders = log.responseHeaders();
            final HttpStatus status = responseHeaders.status();
            if (status.codeClass() != HttpStatusClass.REDIRECTION) {
                return RetryDecision.next();
            }
            if (status == HttpStatus.NOT_MODIFIED || status == HttpStatus.USE_PROXY) {
                return RetryDecision.next();
            }
            final String location = responseHeaders.get(HttpHeaderNames.LOCATION);
            if (isNullOrEmpty(location)) {
                return RetryDecision.next();
            }
            final RequestHeaders requestHeaders = log.requestHeaders();
            final RequestHeadersBuilder builder = requestHeaders.toBuilder();
            final HttpMethod method = requestHeaders.method();
            if (status == HttpStatus.SEE_OTHER && !(method == HttpMethod.GET || method == HttpMethod.HEAD)) {
                // HTTP methods are changed to GET.
                // https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections
                // https://datatracker.ietf.org/doc/html/rfc7231#section-6.4.4
                builder.method(HttpMethod.GET);
            }
            builder.path(location);
            return RetryDecision.retry(Backoff.withoutDelay(), builder.build());
        });
    }
}
