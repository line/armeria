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

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * A formatter that convert {@link RequestLog} into text message.
 */
final class DefaultTextLogFormatter implements LogFormatter {

    static final DefaultTextLogFormatter DEFAULT_INSTANCE = new DefaultTextLogFormatter();

    @Override
    public String formatRequest(RequestLog log, LogSanitizers<RequestHeaders> sanitizers) {
        requireNonNull(log, "log,");
        requireNonNull(sanitizers, "sanitizers,");

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

        final String sanitizedHeaders;
        if (availableProperties.contains(RequestLogProperty.REQUEST_HEADERS) && log.requestHeaders() != null) {
            sanitizedHeaders = sanitize(log, sanitizers.headersSanitizer(), log.requestHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT) && log.requestContent() != null) {
            sanitizedContent = sanitize(log, sanitizers.contentSanitizer(), log.requestContent());
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.REQUEST_TRAILERS)
            && !log.requestTrailers().isEmpty()) {
            sanitizedTrailers = sanitize(log, sanitizers.trailersSanitizer(), log.requestTrailers());
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
            if (availableProperties.contains(RequestLogProperty.SCHEME) && log.scheme() != null) {
                buf.append(log.scheme().uriText());
            } else {
                buf.append(SerializationFormat.UNKNOWN.uriText())
                   .append('+')
                   .append(log.sessionProtocol() != null ? log.sessionProtocol().uriText() : "unknown");
            }

            if (availableProperties.contains(RequestLogProperty.NAME) && log.name() != null) {
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
    public String formatResponse(RequestLog log, LogSanitizers<ResponseHeaders> sanitizers) {
        requireNonNull(log, "log,");
        requireNonNull(sanitizers, "sanitizers,");

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

        final String sanitizedHeaders;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_HEADERS)
            && log.responseHeaders() != null) {
            sanitizedHeaders = sanitize(log, sanitizers.headersSanitizer(), log.responseHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_CONTENT) &&
            log.responseContent() != null) {
            sanitizedContent = sanitize(log, sanitizers.contentSanitizer(), log.responseContent());
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_TRAILERS)
            && !log.responseTrailers().isEmpty()) {
            sanitizedTrailers = sanitize(log, sanitizers.trailersSanitizer(), log.responseTrailers());
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
            } else if (log.responseContentPreview() != null) {
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

    private DefaultTextLogFormatter() {}
}
