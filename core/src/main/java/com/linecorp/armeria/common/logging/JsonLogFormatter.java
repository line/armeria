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
public final class JsonLogFormatter implements LogFormatter {

    private static final Logger logger = LoggerFactory.getLogger(JsonLogFormatter.class);

    static final JsonLogFormatter DEFAULT_INSTANCE = new JsonLogFormatterBuilder().build();

    private final ObjectMapper objectMapper;

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
        final JsonNode sanitizedHeaders;
        if (availableProperties.contains(RequestLogProperty.REQUEST_HEADERS)) {
            sanitizedHeaders = requestHeadersSanitizer.apply(ctx, log.requestHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final JsonNode sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT) && log.requestContent() != null) {
            sanitizedContent = requestContentSanitizer.apply(ctx, log.requestContent());
        } else if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT_PREVIEW) &&
                   log.requestContentPreview() != null) {
            sanitizedContent = requestContentSanitizer.apply(ctx, log.requestContentPreview());
        } else {
            sanitizedContent = null;
        }

        final JsonNode sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.REQUEST_TRAILERS) &&
            !log.requestTrailers().isEmpty()) {
            sanitizedTrailers = requestTrailersSanitizer.apply(ctx, log.requestTrailers());
        } else {
            sanitizedTrailers = null;
        }

        final ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put("startTime",
                       TextFormatter.epochMicros(log.requestStartTimeMicros()).toString());

        if (availableProperties.contains(RequestLogProperty.REQUEST_LENGTH)) {
            objectNode.put("length", TextFormatter.size(log.requestLength()).toString());
        }

        if (availableProperties.contains(RequestLogProperty.REQUEST_END_TIME)) {
            objectNode.put("duration",
                           TextFormatter.elapsed(log.requestDurationNanos()).toString());
        }

        if (requestCauseString != null) {
            objectNode.put("cause", requestCauseString);
        }

        if (availableProperties.contains(RequestLogProperty.SCHEME)) {
            objectNode.put("scheme", log.scheme().uriText());
        } else if (availableProperties.contains(RequestLogProperty.SESSION)) {
            objectNode.put("scheme",
                           SerializationFormat.UNKNOWN.uriText() + '+' +
                           log.sessionProtocol());
        } else {
            objectNode.put("scheme",
                           SerializationFormat.UNKNOWN.uriText() + "+unknown");
        }

        if (availableProperties.contains(RequestLogProperty.NAME)) {
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
        try {
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("Unexpected exception while formatting a request log: {}", log, e);
            return "";
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
        final JsonNode sanitizedHeaders;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_HEADERS)) {
            sanitizedHeaders = responseHeadersSanitizer.apply(ctx, log.responseHeaders());
        } else {
            sanitizedHeaders = null;
        }

        final JsonNode sanitizedContent;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_CONTENT) &&
            log.responseContent() != null) {
            sanitizedContent = responseContentSanitizer.apply(ctx, log.responseContent());
        } else if (availableProperties.contains(RequestLogProperty.RESPONSE_CONTENT_PREVIEW) &&
                   log.responseContentPreview() != null) {
            sanitizedContent = responseContentSanitizer.apply(ctx, log.responseContentPreview());
        } else {
            sanitizedContent = null;
        }

        final JsonNode sanitizedTrailers;
        if (availableProperties.contains(RequestLogProperty.RESPONSE_TRAILERS) &&
            !log.responseTrailers().isEmpty()) {
            sanitizedTrailers = responseTrailersSanitizer.apply(ctx, log.responseTrailers());
        } else {
            sanitizedTrailers = null;
        }

        final ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put("startTime",
                       TextFormatter.epochMicros(log.responseStartTimeMicros()).toString());

        if (availableProperties.contains(RequestLogProperty.RESPONSE_LENGTH)) {
            objectNode.put("length", TextFormatter.size(log.responseLength()).toString());
        }

        if (availableProperties.contains(RequestLogProperty.RESPONSE_END_TIME)) {
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

        final int numChildren = log.children() != null ? log.children().size() : 0;
        if (numChildren > 1) {
            // Append only when there were retries which the numChildren is greater than 1.
            objectNode.put("totalAttempts", String.valueOf(numChildren));
        }
        try {
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            logger.warn("Unexpected exception while formatting a request log: {}", log, e);
            return "";
        }
    }
}
