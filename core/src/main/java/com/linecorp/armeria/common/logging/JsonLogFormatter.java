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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TextFormatter;

/**
 * A formatter that converts a {@link RequestOnlyLog} or {@link RequestLog} into a JSON format message.
 */
@UnstableApi
final class JsonLogFormatter implements LogFormatter {

    private static final Logger logger = LoggerFactory.getLogger(JsonLogFormatter.class);

    static final JsonLogFormatter DEFAULT_INSTANCE = new JsonLogFormatterBuilder().build();

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
            requestHeadersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
            responseHeadersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
            requestTrailersSanitizer;

    private final BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
            responseTrailersSanitizer;

    private final BiFunction<? super RequestContext, Object, ? extends JsonNode> requestContentSanitizer;

    private final BiFunction<? super RequestContext, Object, ? extends JsonNode> responseContentSanitizer;

    private final ObjectMapper objectMapper;

    JsonLogFormatter(
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode> requestHeadersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
                    responseHeadersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
                    requestTrailersSanitizer,
            BiFunction<? super RequestContext, ? super HttpHeaders, ? extends JsonNode>
                    responseTrailersSanitizer,
            BiFunction<? super RequestContext, Object, ? extends JsonNode> requestContentSanitizer,
            BiFunction<? super RequestContext, Object, ? extends JsonNode> responseContentSanitizer,
            ObjectMapper objectMapper
    ) {
        this.requestHeadersSanitizer = requestHeadersSanitizer;
        this.responseHeadersSanitizer = responseHeadersSanitizer;
        this.requestTrailersSanitizer = requestTrailersSanitizer;
        this.responseTrailersSanitizer = responseTrailersSanitizer;
        this.requestContentSanitizer = requestContentSanitizer;
        this.responseContentSanitizer = responseContentSanitizer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String formatRequest(RequestOnlyLog log) {
        requireNonNull(log, "log");

        final int flags = log.availabilityStamp();
        if (!RequestLogProperty.REQUEST_START_TIME.isAvailable(flags)) {
            return "{}";
        }

        try {
            String requestCauseString = null;
            if (RequestLogProperty.REQUEST_CAUSE.isAvailable(flags)) {
                final Throwable cause = log.requestCause();
                if (cause != null) {
                    requestCauseString = cause.toString();
                }
            }

            final RequestContext ctx = log.context();
            final JsonNode sanitizedHeaders;
            if (RequestLogProperty.REQUEST_HEADERS.isAvailable(flags)) {
                sanitizedHeaders = requestHeadersSanitizer.apply(ctx, log.requestHeaders());
            } else {
                sanitizedHeaders = null;
            }

            JsonNode sanitizedContent = null;
            if (RequestLogProperty.REQUEST_CONTENT.isAvailable(flags)) {
                final Object content = log.requestContent();
                if (content != null) {
                    sanitizedContent = requestContentSanitizer.apply(ctx, content);
                }
            } else if (RequestLogProperty.REQUEST_CONTENT_PREVIEW.isAvailable(flags)) {
                final String contentPreview = log.requestContentPreview();
                if (contentPreview != null) {
                    sanitizedContent = requestContentSanitizer.apply(ctx, contentPreview);
                }
            }

            final JsonNode sanitizedTrailers;
            if (RequestLogProperty.REQUEST_TRAILERS.isAvailable(flags) &&
                !log.requestTrailers().isEmpty()) {
                sanitizedTrailers = requestTrailersSanitizer.apply(ctx, log.requestTrailers());
            } else {
                sanitizedTrailers = null;
            }

            final ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("startTime",
                           TextFormatter.epochMicros(log.requestStartTimeMicros()).toString());

            if (RequestLogProperty.REQUEST_LENGTH.isAvailable(flags)) {
                objectNode.put("length", TextFormatter.size(log.requestLength()).toString());
            }

            if (RequestLogProperty.REQUEST_END_TIME.isAvailable(flags)) {
                objectNode.put("duration",
                               TextFormatter.elapsed(log.requestDurationNanos()).toString());
            }

            if (requestCauseString != null) {
                objectNode.put("cause", requestCauseString);
            }

            if (RequestLogProperty.SCHEME.isAvailable(flags)) {
                objectNode.put("scheme", log.scheme().uriText());
            } else if (RequestLogProperty.SESSION.isAvailable(flags)) {
                objectNode.put("scheme",
                               SerializationFormat.UNKNOWN.uriText() + '+' +
                               log.sessionProtocol());
            } else {
                objectNode.put("scheme",
                               SerializationFormat.UNKNOWN.uriText() + "+unknown");
            }

            if (RequestLogProperty.NAME.isAvailable(flags)) {
                objectNode.put("name", log.name());
            }

            if (sanitizedHeaders != null) {
                objectNode.set("headers", sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                objectNode.set("content", sanitizedContent);
            }

            if (sanitizedTrailers != null) {
                objectNode.set("trailers", sanitizedTrailers);
            }
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("Unexpected exception while formatting a request log: {}", log, e);
            return "{}";
        }
    }

    @Override
    public String formatResponse(RequestLog log) {
        requireNonNull(log, "log");

        final int flags = log.availabilityStamp();
        if (!RequestLogProperty.RESPONSE_START_TIME.isAvailable(flags)) {
            return "{}";
        }

        try {
            String responseCauseString = null;
            if (RequestLogProperty.RESPONSE_CAUSE.isAvailable(flags)) {
                final Throwable cause = log.responseCause();
                if (cause != null) {
                    responseCauseString = cause.toString();
                }
            }

            final RequestContext ctx = log.context();
            final JsonNode sanitizedHeaders;
            if (RequestLogProperty.RESPONSE_HEADERS.isAvailable(flags)) {
                sanitizedHeaders = responseHeadersSanitizer.apply(ctx, log.responseHeaders());
            } else {
                sanitizedHeaders = null;
            }

            JsonNode sanitizedContent = null;
            if (RequestLogProperty.RESPONSE_CONTENT.isAvailable(flags)) {
                final Object content = log.responseContent();
                if (content != null) {
                    sanitizedContent = responseContentSanitizer.apply(ctx, content);
                }
            } else if (RequestLogProperty.RESPONSE_CONTENT_PREVIEW.isAvailable(flags)) {
                final String contentPreview = log.responseContentPreview();
                if (contentPreview != null) {
                    sanitizedContent = responseContentSanitizer.apply(ctx, contentPreview);
                }
            }

            final JsonNode sanitizedTrailers;
            if (RequestLogProperty.RESPONSE_TRAILERS.isAvailable(flags) &&
                !log.responseTrailers().isEmpty()) {
                sanitizedTrailers = responseTrailersSanitizer.apply(ctx, log.responseTrailers());
            } else {
                sanitizedTrailers = null;
            }

            final ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("startTime",
                           TextFormatter.epochMicros(log.responseStartTimeMicros()).toString());

            if (RequestLogProperty.RESPONSE_LENGTH.isAvailable(flags)) {
                objectNode.put("length", TextFormatter.size(log.responseLength()).toString());
            }

            if (RequestLogProperty.RESPONSE_END_TIME.isAvailable(flags)) {
                objectNode.put("duration", TextFormatter.elapsed(log.responseDurationNanos()).toString());
                objectNode.put("totalDuration",
                               TextFormatter.elapsed(log.totalDurationNanos()).toString());
            }

            if (responseCauseString != null) {
                objectNode.put("cause", responseCauseString);
            }

            if (sanitizedHeaders != null) {
                objectNode.set("headers", sanitizedHeaders);
            }

            if (sanitizedContent != null) {
                objectNode.set("content", sanitizedContent);
            }

            if (sanitizedTrailers != null) {
                objectNode.set("trailers", sanitizedTrailers);
            }

            final List<RequestLogAccess> children = log.children();
            final int numChildren = children != null ? children.size() : 0;
            if (numChildren > 1) {
                // Append only when there were retries which the numChildren is greater than 1.
                objectNode.put("totalAttempts", String.valueOf(numChildren));
            }
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("Unexpected exception while formatting a response log: {}", log, e);
            return "{}";
        }
    }
}
