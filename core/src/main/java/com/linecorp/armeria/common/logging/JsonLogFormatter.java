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
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TextFormatter;

/**
 * A formatter that converts a {@link RequestOnlyLog} or {@link RequestLog} into a JSON format message.
 */
@UnstableApi
final class JsonLogFormatter implements LogFormatter {

    private static final Logger logger = LoggerFactory.getLogger(JsonLogFormatter.class);

    static final LogFormatter DEFAULT_INSTANCE = new JsonLogFormatterBuilder().build();

    private final HeadersSanitizer<JsonNode> requestHeadersSanitizer;

    private final HeadersSanitizer<JsonNode> responseHeadersSanitizer;

    private final HeadersSanitizer<JsonNode> requestTrailersSanitizer;

    private final HeadersSanitizer<JsonNode> responseTrailersSanitizer;

    private final BiFunction<? super RequestContext, Object, ? extends @Nullable JsonNode>
            requestContentSanitizer;

    private final BiFunction<? super RequestContext, Object, ? extends @Nullable JsonNode>
            responseContentSanitizer;

    private final ObjectMapper objectMapper;

    JsonLogFormatter(
            HeadersSanitizer<JsonNode> requestHeadersSanitizer,
            HeadersSanitizer<JsonNode> responseHeadersSanitizer,
            HeadersSanitizer<JsonNode> requestTrailersSanitizer,
            HeadersSanitizer<JsonNode> responseTrailersSanitizer,
            BiFunction<? super RequestContext, Object, ? extends @Nullable JsonNode> requestContentSanitizer,
            BiFunction<? super RequestContext, Object, ? extends @Nullable JsonNode> responseContentSanitizer,
            ObjectMapper objectMapper) {
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
        final RequestContext ctx = log.context();
        if (!RequestLogProperty.REQUEST_START_TIME.isAvailable(flags)) {
            return "{\"type\": \"request\"}";
        }

        try {
            String requestCauseString = null;
            if (RequestLogProperty.REQUEST_CAUSE.isAvailable(flags)) {
                final Throwable cause = log.requestCause();
                if (cause != null) {
                    requestCauseString = cause.toString();
                }
            }

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
            }
            if (sanitizedContent == null && RequestLogProperty.REQUEST_CONTENT_PREVIEW.isAvailable(flags)) {
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
            objectNode.put("type", "request");
            objectNode.put("startTime",
                           TextFormatter.epochMicros(log.requestStartTimeMicros()).toString());

            if (RequestLogProperty.SESSION.isAvailable(flags)) {
                final ObjectNode connectionNode =
                        maybeCreateConnectionTimings(log.connectionTimings(), objectMapper);
                if (connectionNode != null) {
                    objectNode.set("connection", connectionNode);
                }
            }

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

            final int currentAttempt = log.currentAttempt();
            if (currentAttempt > 0) {
                objectNode.put("currentAttempt", currentAttempt);
            }

            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("Unexpected exception while formatting a request log: {}", log, e);
            return "{}";
        }
    }

    @Nullable
    private static ObjectNode maybeCreateConnectionTimings(@Nullable ClientConnectionTimings timings,
                                                           ObjectMapper objectMapper) {
        if (timings == null) {
            return null;
        }

        final ObjectNode objectNode = objectMapper.createObjectNode();
        final ObjectNode connectionObjectNode =
                startTimeAndDuration(objectMapper,
                                     timings.connectionAcquisitionDurationNanos(),
                                     timings.connectionAcquisitionStartTimeMicros());
        objectNode.set("total", connectionObjectNode);

        if (timings.dnsResolutionDurationNanos() >= 0) {
            final ObjectNode dnsObjectNode =
                    startTimeAndDuration(objectMapper,
                                         timings.dnsResolutionDurationNanos(),
                                         timings.dnsResolutionStartTimeMicros());
            objectNode.set("dns", dnsObjectNode);
        }
        if (timings.pendingAcquisitionDurationNanos() >= 0) {
            final ObjectNode pendingObjectNode =
                    startTimeAndDuration(objectMapper,
                                         timings.pendingAcquisitionDurationNanos(),
                                         timings.pendingAcquisitionStartTimeMicros());
            objectNode.set("pending", pendingObjectNode);
        }
        if (timings.socketConnectDurationNanos() >= 0) {
            final ObjectNode socketObjectNode =
                    startTimeAndDuration(objectMapper,
                                         timings.socketConnectDurationNanos(),
                                         timings.socketConnectStartTimeMicros());
            objectNode.set("socket", socketObjectNode);
        }
        if (timings.tlsHandshakeDurationNanos() >= 0) {
            final ObjectNode tlsObjectNode =
                    startTimeAndDuration(objectMapper,
                                         timings.tlsHandshakeDurationNanos(),
                                         timings.tlsHandshakeStartTimeMicros());
            objectNode.set("tls", tlsObjectNode);
        }
        return objectNode;
    }

    static ObjectNode startTimeAndDuration(ObjectMapper objectMapper, long durationNanos,
                                             long startTimeMicros) {
        final ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put("durationNanos", durationNanos);
        objectNode.put("startTimeMicros", startTimeMicros);
        return objectNode;
    }

    @Override
    public String formatResponse(RequestLog log) {
        requireNonNull(log, "log");

        final int flags = log.availabilityStamp();
        final RequestContext ctx = log.context();
        if (!RequestLogProperty.RESPONSE_START_TIME.isAvailable(flags)) {
            return "{\"type\": \"response\"}";
        }

        try {
            String responseCauseString = null;
            if (RequestLogProperty.RESPONSE_CAUSE.isAvailable(flags)) {
                final Throwable cause = log.responseCause();
                if (cause != null) {
                    responseCauseString = cause.toString();
                }
            }

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
            }
            if (sanitizedContent == null && RequestLogProperty.RESPONSE_CONTENT_PREVIEW.isAvailable(flags)) {
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
            objectNode.put("type", "response");
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
            final int numChildren = children.size();
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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("objectMapper", objectMapper)
                          .add("requestHeadersSanitizer", requestHeadersSanitizer)
                          .add("requestContentSanitizer", requestContentSanitizer)
                          .add("requestTrailersSanitizer", requestTrailersSanitizer)
                          .add("responseHeadersSanitizer", responseHeadersSanitizer)
                          .add("responseContentSanitizer", responseContentSanitizer)
                          .add("responseTrailersSanitizer", responseTrailersSanitizer)
                          .toString();
    }
}
