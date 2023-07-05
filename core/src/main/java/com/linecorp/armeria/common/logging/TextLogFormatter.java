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

import java.util.List;
import java.util.function.BiFunction;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * A formatter that converts {@link RequestOnlyLog} or {@link RequestLog} into text message.
 */
@UnstableApi
final class TextLogFormatter implements LogFormatter {

    static final LogFormatter DEFAULT_INSTANCE = new TextLogFormatterBuilder().build();

    private final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable String> requestHeadersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable String> responseHeadersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable String> requestTrailersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders,
            ? extends @Nullable String> responseTrailersSanitizer;

    private final BiFunction<? super RequestContext, Object,
            ? extends @Nullable String> requestContentSanitizer;

    private final BiFunction<? super RequestContext, Object,
            ? extends @Nullable String> responseContentSanitizer;

    private final boolean includeContext;

    TextLogFormatter(
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> requestHeadersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> responseHeadersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders,
                    ? extends @Nullable String> responseTrailersSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable String> requestContentSanitizer,
            BiFunction<? super RequestContext, Object,
                    ? extends @Nullable String> responseContentSanitizer,
            boolean includeContext) {
        this.requestHeadersSanitizer = requestHeadersSanitizer;
        this.responseHeadersSanitizer = responseHeadersSanitizer;
        this.requestTrailersSanitizer = requestTrailersSanitizer;
        this.responseTrailersSanitizer = responseTrailersSanitizer;
        this.requestContentSanitizer = requestContentSanitizer;
        this.responseContentSanitizer = responseContentSanitizer;
        this.includeContext = includeContext;
    }

    @Override
    public String formatRequest(RequestOnlyLog log) {
        requireNonNull(log, "log");

        final int flags = log.availabilityStamp();
        final RequestContext ctx = log.context();
        if (!RequestLogProperty.REQUEST_START_TIME.isAvailable(flags)) {
            if (includeContext) {
                return ctx + " Request: {}";
            } else {
                return "Request: {}";
            }
        }

        String requestCauseString = null;
        if (RequestLogProperty.REQUEST_CAUSE.isAvailable(flags)) {
            final Throwable cause = log.requestCause();
            if (cause != null) {
                requestCauseString = cause.toString();
            }
        }

        final String sanitizedHeaders;
        if (RequestLogProperty.REQUEST_HEADERS.isAvailable(flags)) {
            sanitizedHeaders = requestHeadersSanitizer.apply(ctx, log.requestHeaders());
        } else {
            sanitizedHeaders = null;
        }

        String sanitizedContent = null;
        if (RequestLogProperty.REQUEST_CONTENT.isAvailable(flags)) {
            final Object content = log.requestContent();
            if (content != null) {
                sanitizedContent = requestContentSanitizer.apply(ctx, content);
            }
        }
        if (sanitizedContent == null && RequestLogProperty.REQUEST_CONTENT_PREVIEW.isAvailable(flags)) {
            final String contentPreview = log.requestContentPreview();
            if (contentPreview != null) {
                sanitizedContent = requestContentSanitizer.apply(ctx, contentPreview);
            }
        }

        final String sanitizedTrailers;
        if (RequestLogProperty.REQUEST_TRAILERS.isAvailable(flags) &&
            !log.requestTrailers().isEmpty()) {
            sanitizedTrailers = requestTrailersSanitizer.apply(ctx, log.requestTrailers());
        } else {
            sanitizedTrailers = null;
        }

        final String ctxString;
        if (includeContext) {
            // ctx internally uses TemporaryThreadLocals, so we should call ctx.toString() outside of acquire().
            ctxString = ctx.toString() + ' ';
        } else {
            ctxString = null;
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            if (ctxString != null) {
                buf.append(ctxString);
            }
            buf.append("Request: {startTime=");
            TextFormatter.appendEpochMicros(buf, log.requestStartTimeMicros());

            if (RequestLogProperty.REQUEST_LENGTH.isAvailable(flags)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, log.requestLength());
            }

            if (RequestLogProperty.REQUEST_END_TIME.isAvailable(flags)) {
                buf.append(", duration=");
                TextFormatter.appendElapsed(buf, log.requestDurationNanos());
            }

            if (requestCauseString != null) {
                buf.append(", cause=").append(requestCauseString);
            }

            buf.append(", scheme=");
            if (RequestLogProperty.SCHEME.isAvailable(flags)) {
                buf.append(log.scheme().uriText());
            } else if (RequestLogProperty.SESSION.isAvailable(flags)) {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append(log.sessionProtocol());
            } else {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append("unknown");
            }

            if (RequestLogProperty.NAME.isAvailable(flags)) {
                buf.append(", name=").append(log.name());
            }

            if (sanitizedHeaders != null) {
                buf.append(", headers=").append(sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                buf.append(", content=").append(sanitizedContent);
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

        final int flags = log.availabilityStamp();
        final RequestContext ctx = log.context();
        if (!RequestLogProperty.RESPONSE_START_TIME.isAvailable(flags)) {
            if (includeContext) {
                return ctx + " Response: {}";
            } else {
                return "Response: {}";
            }
        }

        String responseCauseString = null;
        if (RequestLogProperty.RESPONSE_CAUSE.isAvailable(flags)) {
            final Throwable cause = log.responseCause();
            if (cause != null) {
                responseCauseString = cause.toString();
            }
        }

        final String sanitizedHeaders;
        if (RequestLogProperty.RESPONSE_HEADERS.isAvailable(flags)) {
            sanitizedHeaders = responseHeadersSanitizer.apply(ctx, log.responseHeaders());
        } else {
            sanitizedHeaders = null;
        }

        String sanitizedContent = null;
        if (RequestLogProperty.RESPONSE_CONTENT.isAvailable(flags)) {
            final Object content = log.responseContent();
            if (content != null) {
                sanitizedContent = responseContentSanitizer.apply(ctx, content);
            }
        }
        if (sanitizedContent == null && RequestLogProperty.RESPONSE_CONTENT_PREVIEW.isAvailable(flags)) {
            final String contentPreview = log.responseContentPreview();
            if (contentPreview != null) {
                sanitizedContent = responseContentSanitizer.apply(ctx, contentPreview);
            }
        }

        final String sanitizedTrailers;
        if (RequestLogProperty.RESPONSE_TRAILERS.isAvailable(flags) &&
            !log.responseTrailers().isEmpty()) {
            sanitizedTrailers = responseTrailersSanitizer.apply(ctx, log.responseTrailers());
        } else {
            sanitizedTrailers = null;
        }

        final String ctxString;
        if (includeContext) {
            // ctx internally uses TemporaryThreadLocals, so we should call ctx.toString() outside of acquire().
            ctxString = ctx.toString() + ' ';
        } else {
            ctxString = null;
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            if (ctxString != null) {
                buf.append(ctxString);
            }
            buf.append("Response: {startTime=");
            TextFormatter.appendEpochMicros(buf, log.responseStartTimeMicros());

            if (RequestLogProperty.RESPONSE_LENGTH.isAvailable(flags)) {
                buf.append(", length=");
                TextFormatter.appendSize(buf, log.responseLength());
            }

            if (RequestLogProperty.RESPONSE_END_TIME.isAvailable(flags)) {
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
            }

            if (sanitizedTrailers != null) {
                buf.append(", trailers=").append(sanitizedTrailers);
            }
            buf.append('}');

            final List<RequestLogAccess> children = log.children();
            final int numChildren = children.size();
            if (numChildren > 1) {
                // Append only when there were retries which the numChildren is greater than 1.
                buf.append(", {totalAttempts=");
                buf.append(numChildren);
                buf.append('}');
            }

            return buf.toString();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("requestHeadersSanitizer", requestHeadersSanitizer)
                          .add("requestContentSanitizer", requestContentSanitizer)
                          .add("requestTrailersSanitizer", requestTrailersSanitizer)
                          .add("responseHeadersSanitizer", responseHeadersSanitizer)
                          .add("responseContentSanitizer", responseContentSanitizer)
                          .add("responseTrailersSanitizer", responseTrailersSanitizer)
                          .toString();
    }
}
