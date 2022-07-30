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

import java.io.IOException;
import java.io.StringWriter;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A formatter that convert {@link RequestLog} into json format message.
 */
@UnstableApi
final class JsonLogFormatter implements LogFormatter {

    static final JsonLogFormatter DEFAULT_INSTANCE = new JsonLogFormatter();

    private static final Logger logger = LoggerFactory.getLogger(JsonLogFormatter.class);

    private final ObjectMapper objectMapper;

    JsonLogFormatter() {
        this(JacksonUtil.newDefaultObjectMapper());
    }

    JsonLogFormatter(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String formatRequest(RequestLog log, LogSanitizer sanitizer) {
        requireNonNull(log, "log,");
        requireNonNull(sanitizer, "sanitizer,");

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
            sanitizedHeaders = sanitizer.sanitizeHeaders(ctx, log.requestHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT) && log.requestContent() != null) {
            sanitizedContent = sanitizer.sanitizeContent(ctx, log.requestContent());
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.REQUEST_TRAILERS) &&
            !log.requestTrailers().isEmpty()) {
            sanitizedTrailers = sanitizer.sanitizeTrailers(ctx, log.requestTrailers());
        } else {
            sanitizedTrailers = null;
        }

        try {
            final StringWriter writer = new StringWriter(512);
            final JsonGenerator gen = objectMapper.createGenerator(writer);
            gen.writeStartObject();
            gen.writeStringField("startTime",
                                 TextFormatter.epochMicros(log.requestStartTimeMicros()).toString());

            if (availableProperties.contains(RequestLogProperty.REQUEST_LENGTH)) {
                gen.writeStringField("length", TextFormatter.size(log.requestLength()).toString());
            }

            if (availableProperties.contains(RequestLogProperty.REQUEST_END_TIME)) {
                gen.writeStringField("duration",
                                     TextFormatter.elapsed(log.requestDurationNanos()).toString());
            }

            if (requestCauseString != null) {
                gen.writeStringField("cause", requestCauseString);
            }

            if (availableProperties.contains(RequestLogProperty.SCHEME)) {
                gen.writeStringField("scheme", log.scheme().uriText());
            } else if (availableProperties.contains(RequestLogProperty.SESSION)) {
                gen.writeStringField("scheme",
                                     SerializationFormat.UNKNOWN.uriText() + '+' +
                                     log.sessionProtocol());
            } else {
                gen.writeStringField("scheme",
                                     SerializationFormat.UNKNOWN.uriText() + "+unknown");
            }

            if (availableProperties.contains(RequestLogProperty.NAME)) {
                gen.writeStringField("name", log.name());
            }

            if (sanitizedHeaders != null) {
                gen.writeStringField("headers", sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                gen.writeStringField("content", sanitizedContent);
            } else if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT_PREVIEW) &&
                       log.requestContentPreview() != null) {
                gen.writeStringField("contentPreview", log.requestContentPreview());
            }

            if (sanitizedTrailers != null) {
                gen.writeStringField("trailers", sanitizedTrailers);
            }
            gen.writeEndObject();
            gen.close();
            return writer.toString();
        } catch (IOException e) {
            logger.warn("Unexpected exception while formatting a request log", e);
            return "";
        }
    }

    @Override
    public String formatResponse(RequestLog log, LogSanitizer sanitizer) {
        requireNonNull(log, "log,");
        requireNonNull(sanitizer, "sanitizer,");

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
            sanitizedHeaders = sanitizer.sanitizeHeaders(ctx, log.responseHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final String sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_CONTENT) &&
            log.responseContent() != null) {
            sanitizedContent = sanitizer.sanitizeContent(ctx, log.responseContent());
        } else {
            sanitizedContent = null;
        }

        final String sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_TRAILERS) &&
            !log.responseTrailers().isEmpty()) {
            sanitizedTrailers = sanitizer.sanitizeTrailers(ctx, log.responseTrailers());
        } else {
            sanitizedTrailers = null;
        }

        try {
            final StringWriter writer = new StringWriter(512);
            final JsonGenerator gen = objectMapper.createGenerator(writer);
            gen.writeStartObject();
            gen.writeStringField("startTime",
                                 TextFormatter.epochMicros(log.responseStartTimeMicros()).toString());

            if (availableProperties.contains(RequestLogProperty.RESPONSE_LENGTH)) {
                gen.writeStringField("length", TextFormatter.size(log.responseLength()).toString());
            }

            if (availableProperties.contains(RequestLogProperty.RESPONSE_END_TIME)) {
                gen.writeStringField("duration", TextFormatter.elapsed(log.responseDurationNanos()).toString());
                gen.writeStringField("totalDuration",
                                     TextFormatter.elapsed(log.totalDurationNanos()).toString());
            }

            if (responseCauseString != null) {
                gen.writeStringField("cause", responseCauseString);
            }

            if (sanitizedHeaders != null) {
                gen.writeStringField("headers", sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                gen.writeStringField("content", sanitizedContent);
            } else if (log.responseContentPreview() != null) {
                gen.writeStringField("contentPreview", log.responseContentPreview());
            }

            if (sanitizedTrailers != null) {
                gen.writeStringField("trailers", sanitizedTrailers);
            }

            final int numChildren = log.children() != null ? log.children().size() : 0;
            if (numChildren > 1) {
                // Append only when there were retries which the numChildren is greater than 1.
                gen.writeStringField("totalAttempts", String.valueOf(numChildren));
            }
            gen.writeEndObject();
            gen.close();
            return writer.toString();
        } catch (IOException e) {
            logger.warn("Unexpected exception while formatting a response log", e);
            return "";
        }
    }
}
