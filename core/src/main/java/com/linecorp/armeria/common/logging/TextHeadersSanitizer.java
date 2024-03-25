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

package com.linecorp.armeria.common.logging;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link String}.
 */
final class TextHeadersSanitizer implements HeadersSanitizer<String> {

    static final HeadersSanitizer<String> INSTANCE = new TextHeadersSanitizerBuilder().build();

    private final Set<AsciiString> sensitiveHeaders;

    private final HeaderMaskingFunction maskingFunction;

    TextHeadersSanitizer(Set<AsciiString> sensitiveHeaders,
                         HeaderMaskingFunction maskingFunction) {
        this.sensitiveHeaders = sensitiveHeaders;
        this.maskingFunction = maskingFunction;
    }

    @Override
    public String sanitize(RequestContext ctx, HttpHeaders headers) {
        if (headers.isEmpty()) {
            return headers.isEndOfStream() ? "[EOS]" : "[]";
        }

        final StringBuilder sb = new StringBuilder();
        if (headers.isEndOfStream()) {
            sb.append("[EOS], ");
        } else {
            sb.append('[');
        }

        maskHeaders(headers, sensitiveHeaders, maskingFunction,
                    (header, values) -> sb.append(header).append('=')
                                          .append(values.size() > 1 ?
                                                  values.toString() : values.get(0)).append(", "));

        sb.setCharAt(sb.length() - 2, ']');
        return sb.substring(0, sb.length() - 1);
    }

    static void maskHeaders(
            HttpHeaders headers, Set<AsciiString> sensitiveHeaders,
            HeaderMaskingFunction maskingFunction,
            BiConsumer<AsciiString, List<String>> consumer) {
        for (AsciiString headerName : headers.names()) {
            List<String> values = headers.getAll(headerName);
            if (sensitiveHeaders.contains(headerName)) {
                // Mask the header values.
                if (values.size() == 1) {
                    final String masked = maskingFunction.mask(headerName, values.get(0));
                    if (masked == null) {
                        values = ImmutableList.of();
                    } else {
                        values = ImmutableList.of(masked);
                    }
                } else {
                    values = values.stream()
                                   .map(value -> maskingFunction.mask(headerName, value))
                                   .filter(Objects::nonNull)
                                   .collect(toImmutableList());
                }
            }
            if (!values.isEmpty()) {
                consumer.accept(headerName, values);
            }
        }
    }
}
