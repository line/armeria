/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * A formatter that converts {@link RequestLog} into text message.
 */
@UnstableApi
class TextLogFormatter implements LogFormatter {

    static final TextLogFormatter DEFAULT_INSTANCE = new TextLogFormatterBuilder().build();

    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestHeadersSanitizer;

    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> responseHeadersSanitizer;

    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestTrailersSanitizer;

    private BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> responseTrailersSanitizer;

    private BiFunction<? super RequestContext, Object, ? extends String> requestContentSanitizer;

    private BiFunction<? super RequestContext, Object, ? extends String> responseContentSanitizer;

    private BiFunction<? super RequestContext, ? super Throwable, ? extends String> responseCauseSanitizer;

    TextLogFormatter(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestHeadersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> responseHeadersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends String> responseTrailersSanitizer,
            BiFunction<? super RequestContext, Object, ? extends String> requestContentSanitizer,
            BiFunction<? super RequestContext, Object, ? extends String> responseContentSanitizer,
            BiFunction<? super RequestContext, ? super Throwable, ? extends String> responseCauseSanitizer
    ) {
        this.requestHeadersSanitizer = requestHeadersSanitizer;
        this.responseHeadersSanitizer = responseHeadersSanitizer;
        this.requestTrailersSanitizer = requestTrailersSanitizer;
        this.responseTrailersSanitizer = responseTrailersSanitizer;
        this.requestContentSanitizer = requestContentSanitizer;
        this.responseContentSanitizer = responseContentSanitizer;
        this.responseCauseSanitizer = responseCauseSanitizer;
    }

    @Override
    public String formatRequest(RequestLog log) {
        requireNonNull(log, "log");

        final Set<RequestLogProperty> availableProperties = log.availableProperties();
        if (!availableProperties.contains(RequestLogProperty.REQUEST_START_TIME)) {
            return "{}";
        }

        final String requestCauseString;
        if (availableProperties.contains(RequestLogProperty.REQUEST_CAUSE) && log.requestCause() != null) {
            requestCauseString = String.valueOf(log.requestCause());
        } else {
            requestCauseString = null;
        }

        final RequestContext ctx = log.context();
        final String sanitizedHeaders;
        if (availableProperties.contains(RequestLogProperty.REQUEST_HEADERS)) {
            sanitizedHeaders = requestHeadersSanitizer.apply(ctx, log.requestHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT) && log.requestContent() != null) {
            sanitizedContent = requestContentSanitizer.apply(ctx, log.requestContent());
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.REQUEST_TRAILERS) &&
            !log.requestTrailers().isEmpty()) {
            sanitizedTrailers = requestTrailersSanitizer.apply(ctx, log.requestTrailers());
        } else {
            sanitizedTrailers = null;
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("{startTime=");
            TextFormatter.appendEpochMicros(buf, log.requestStartTimeMicros());

            if (availableProperties.contains(RequestLogProperty.REQUEST_LENGTH)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, log.requestLength());
            }

            if (availableProperties.contains(RequestLogProperty.REQUEST_END_TIME)) {
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, log.requestDurationNanos());
            }

            if (requestCauseString != null) {
                buf.append(", cause=").append(requestCauseString);
            }

            buf.append(", scheme=");
            if (availableProperties.contains(RequestLogProperty.SCHEME)) {
                buf.append(log.scheme().uriText());
            } else if (availableProperties.contains(RequestLogProperty.SESSION)) {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append(log.sessionProtocol());
            } else {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append("unknown");
            }

            if (availableProperties.contains(RequestLogProperty.NAME)) {
                buf.append(", name=").append(log.name());
            }

            if (sanitizedHeaders != null) {
                buf.append(", headers=").append(sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                buf.append(", content=").append(sanitizedContent);
            } else if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT_PREVIEW) &&
                       log.requestContentPreview() != null) {
                buf.append(", contentPreview=").append(log.requestContentPreview());
            }

            if (sanitizedTrailers != null) {
                buf.append(", trailers=").append(sanitizedTrailers);
            }
            buf.append('}');

            return buf.toString();
        }
    }

    @Override
    public String formatResponse(RequestLog log) {
        requireNonNull(log, "log");

        final Set<RequestLogProperty> availableProperties = log.availableProperties();
        if (!availableProperties.contains(RequestLogProperty.RESPONSE_START_TIME)) {
            return "{}";
        }

        final String responseCauseString;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_CAUSE) && log.responseCause() != null) {
            responseCauseString = String.valueOf(log.responseCause());
        } else {
            responseCauseString = null;
        }

        final RequestContext ctx = log.context();
        final String sanitizedHeaders;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_HEADERS)) {
            sanitizedHeaders = responseHeadersSanitizer.apply(ctx, log.responseHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_CONTENT) &&
            log.responseContent() != null) {
            sanitizedContent = responseContentSanitizer.apply(ctx, log.responseContent());
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_TRAILERS) &&
            !log.responseTrailers().isEmpty()) {
            sanitizedTrailers = responseTrailersSanitizer.apply(ctx, log.responseTrailers());
        } else {
            sanitizedTrailers = null;
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("{startTime=");
            TextFormatter.appendEpochMicros(buf, log.responseStartTimeMicros());

            if (availableProperties.contains(RequestLogProperty.RESPONSE_LENGTH)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, log.responseLength());
            }

            if (availableProperties.contains(RequestLogProperty.RESPONSE_END_TIME)) {
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, log.responseDurationNanos());
                buf.append(", totalDuration=");
                TextFormatter.appendElapsed(buf, log.totalDurationNanos());
            }

            if (responseCauseString != null) {
                buf.append(", cause=").append(responseCauseString);
            }

            if (sanitizedHeaders != null) {
                buf.append(", headers=").append(sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                buf.append(", content=").append(sanitizedContent);
            } else if (availableProperties.contains(RequestLogProperty.RESPONSE_CONTENT_PREVIEW) &&
                       log.responseContentPreview() != null) {
                buf.append(", contentPreview=").append(log.responseContentPreview());
            }

            if (sanitizedTrailers != null) {
                buf.append(", trailers=").append(sanitizedTrailers);
            }
            buf.append('}');

            final int numChildren = log.children() != null ? log.children().size() : 0;
            if (numChildren > 1) {
                // Append only when there were retries which the numChildren is greater than 1.
                buf.append(", {totalAttempts=");
                buf.append(numChildren);
                buf.append('}');
            }

            return buf.toString();
        }
    }

    private TextLogFormatter() {}
}
