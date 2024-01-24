/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link String}.
 */
final class TextHeadersSanitizer implements HeadersSanitizer<String> {

    static final HeadersSanitizer<String> INSTANCE = new TextHeadersSanitizerBuilder().build();

    private final Set<AsciiString> maskingHeaders;

    private final Function<String, String> maskingFunction;

    TextHeadersSanitizer(Set<AsciiString> maskingHeaders, Function<String, String> maskingFunction) {
        this.maskingHeaders = maskingHeaders;
        this.maskingFunction = maskingFunction;
    }

    @Override
    public String apply(RequestContext ctx, HttpHeaders headers) {
        if (headers.isEmpty()) {
            return headers.isEndOfStream() ? "[EOS]" : "[]";
        }

        final StringBuilder sb = new StringBuilder();
        if (headers.isEndOfStream()) {
            sb.append("[EOS], ");
        } else {
            sb.append('[');
        }

        maskHeaders(headers, maskingHeaders, maskingFunction,
                    (header, values) -> sb.append(header).append('=')
                                          .append(values.size() > 1 ?
                                                  values.toString() : values.get(0)).append(", "));

        sb.setCharAt(sb.length() - 2, ']');
        return sb.substring(0, sb.length() - 1);
    }

    static void maskHeaders(
            HttpHeaders headers, Set<AsciiString> maskingHeaders, Function<String, String> maskingFunction,
            final BiConsumer<AsciiString, List<String>> consumer) {
        final Map<AsciiString, List<String>> headersWithValuesAsList = new LinkedHashMap<>();
        for (Map.Entry<AsciiString, String> entry : headers) {
            final AsciiString header = entry.getKey().toLowerCase();
            final String value = maskingHeaders.contains(header) ? maskingFunction.apply(entry.getValue())
                                                                 : entry.getValue();
            headersWithValuesAsList.computeIfAbsent(header, k -> new ArrayList<>()).add(value);
        }

        final Set<Map.Entry<AsciiString, List<String>>> entries = headersWithValuesAsList.entrySet();
        for (Map.Entry<AsciiString, List<String>> entry : entries) {
            final AsciiString header = entry.getKey();
            final List<String> values = entry.getValue();
            consumer.accept(header, values);
        }
    }
}
