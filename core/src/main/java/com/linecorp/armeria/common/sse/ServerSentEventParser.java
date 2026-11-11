/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common.sse;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;

final class ServerSentEventParser {

    private final String rawEvent;
    private final boolean stripBom;
    private final ServerSentEventBuilder builder = ServerSentEvent.builder();
    @Nullable
    private StringBuilder data;
    @Nullable
    private StringBuilder comment;

    ServerSentEventParser(String rawEvent) {
        this(rawEvent, true);
    }

    ServerSentEventParser(String rawEvent, boolean stripBom) {
        this.rawEvent = requireNonNull(rawEvent, "rawEvent");
        this.stripBom = stripBom;
    }

    ServerSentEvent parse() {
        // stripBom is true only for the first block of a stream or a standalone parse.
        int start = stripBom && !rawEvent.isEmpty() && rawEvent.charAt(0) == '\uFEFF' ? 1 : 0;

        // Skip leading blank lines, including the empty line left after stripping a BOM.
        while (start < rawEvent.length()) {
            final char ch = rawEvent.charAt(start);
            if (ch != '\r' && ch != '\n') {
                break;
            }
            start++;
        }

        for (int i = start; i < rawEvent.length(); i++) {
            final char ch = rawEvent.charAt(i);
            if (ch != '\r' && ch != '\n') {
                continue;
            }

            if (i == start) {
                // A blank line ends this event. Do not merge later blocks into it.
                return build();
            }

            parseLine(rawEvent.substring(start, i));

            if (ch == '\r' && i + 1 < rawEvent.length() && rawEvent.charAt(i + 1) == '\n') {
                // Consume CRLF as one line ending so its LF is not mistaken for an empty line.
                i++;
            }
            start = i + 1;
        }

        if (start < rawEvent.length()) {
            // Standalone parsing also accepts a final field without a line ending.
            parseLine(rawEvent.substring(start));
        }

        return build();
    }

    private void parseLine(String line) {
        final int colon = line.indexOf(':');
        // SSE treats a line without a colon as a field with an empty value.
        final String field = colon < 0 ? line : line.substring(0, colon);
        int valueStart = colon < 0 ? line.length() : colon + 1;
        if (valueStart < line.length() && line.charAt(valueStart) == ' ') {
            valueStart++;
        }

        final String value = line.substring(valueStart);
        if (colon == 0) {
            if (comment == null) {
                comment = new StringBuilder();
            }

            comment.append(value).append('\n');
            return;
        }

        switch (field) {
            case "data":
                if (data == null) {
                    data = new StringBuilder();
                }

                data.append(value).append('\n');
                break;
            case "id":
                // SSE requires ignoring ID fields that contain U+0000.
                if (value.indexOf('\u0000') < 0) {
                    builder.id(value);
                }
                break;
            case "event":
                builder.event(value);
                break;
            case "retry":
                parseRetry(value);
                break;
            default:
                break;
        }
    }

    private void parseRetry(String value) {
        if (value.isEmpty()) {
            return;
        }

        // Long.parseLong accepts signs and Unicode digits, but SSE permits only ASCII digits.
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return;
            }
        }

        try {
            builder.retry(Duration.ofMillis(Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            // Ignore retry values outside the supported millisecond range.
        }
    }

    private ServerSentEvent build() {
        final StringBuilder data = this.data;
        if (data != null) {
            builder.data(data.substring(0, data.length() - 1));
        }

        final StringBuilder comment = this.comment;
        if (comment != null) {
            builder.comment(comment.substring(0, comment.length() - 1));
        }

        return builder.build();
    }
}
