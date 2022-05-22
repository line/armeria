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

import java.util.LinkedHashMap;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.util.TextFormatter;

/**
 * A formatter that convert {@link RequestLog} into json format message.
 */
public class JsonLogFormat implements LogFormat {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static String toJson(LinkedHashMap<String, String> contents) {
        try {
            return objectMapper.writeValueAsString(contents);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert to json", e);
        }
    }

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
        if (log.requestHeaders() != null) {
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
        if (!log.requestTrailers().isEmpty()) {
            sanitizedTrailers = sanitize(log, sanitizers.trailersSanitizer(), log.requestTrailers());
        } else {
            sanitizedTrailers = null;
        }

        final LinkedHashMap<String, String> contents = new LinkedHashMap<>();
        contents.put("startTime", TextFormatter.epochMicros(log.requestStartTimeMicros()).toString());

        if (availableProperties.contains(RequestLogProperty.REQUEST_LENGTH)) {
            contents.put("length", TextFormatter.size(log.requestLength()).toString());
        }

        if (availableProperties.contains(RequestLogProperty.REQUEST_END_TIME)) {
            contents.put("duration", TextFormatter.elapsed(log.requestDurationNanos()).toString());
        }

        if (requestCauseString != null) {
            contents.put("cause", requestCauseString);
        }

        if (log.scheme() != null) {
            contents.put("scheme", log.scheme().uriText());
        } else {
            contents.put("scheme",
                         SerializationFormat.UNKNOWN.uriText() + '+' + log.sessionProtocol() != null ?
                         log.sessionProtocol().uriText() : "unknown");
        }

        if (log.name() != null) {
            contents.put("name", log.name());
        }

        if (sanitizedHeaders != null) {
            contents.put("headers", sanitizedHeaders);
        }

        if (sanitizedContent != null) {
            contents.put("content", sanitizedContent);
        } else if (availableProperties.contains(RequestLogProperty.REQUEST_CONTENT_PREVIEW) &&
                   log.requestContentPreview() != null) {
            contents.put("contentPreview", log.requestContentPreview());
        }

        if (sanitizedTrailers != null) {
            contents.put("trailers", sanitizedTrailers);
        }

        return toJson(contents);
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
        if (log.responseHeaders() != null) {
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
        if (!log.responseTrailers().isEmpty()) {
            sanitizedTrailers = sanitize(log, sanitizers.trailersSanitizer(), log.responseTrailers());
        } else {
            sanitizedTrailers = null;
        }

        final LinkedHashMap<String, String> contents = new LinkedHashMap<>();
        contents.put("startTime", TextFormatter.epochMicros(log.responseStartTimeMicros()).toString());

        if (availableProperties.contains(RequestLogProperty.RESPONSE_LENGTH)) {
            contents.put("length", TextFormatter.size(log.responseLength()).toString());
        }

        if (availableProperties.contains(RequestLogProperty.RESPONSE_END_TIME)) {
            contents.put("duration", TextFormatter.elapsed(log.responseDurationNanos()).toString());
            contents.put("totalDuration", TextFormatter.elapsed(log.totalDurationNanos()).toString());
        }

        if (responseCauseString != null) {
            contents.put("cause", responseCauseString);
        }

        if (sanitizedHeaders != null) {
            contents.put("headers", sanitizedHeaders);
        }

        if (sanitizedContent != null) {
            contents.put("content", sanitizedContent);
        } else if (log.responseContentPreview() != null) {
            contents.put("contentPreview", log.responseContentPreview());
        }

        if (sanitizedTrailers != null) {
            contents.put("trailers", sanitizedTrailers);
        }

        final int numChildren = log.children() != null ? log.children().size() : 0;
        if (numChildren > 1) {
            // Append only when there were retries which the numChildren is greater than 1.
            contents.put("totalAttempts", String.valueOf(numChildren));
        }
        return toJson(contents);
    }
}
