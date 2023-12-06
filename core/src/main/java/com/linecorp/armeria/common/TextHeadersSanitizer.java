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

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link String}.
 */
public final class TextHeadersSanitizer implements HeadersSanitizer<String> {

    static final HeadersSanitizer<String> INSTANCE = new TextHeadersSanitizerBuilder().build();

    private final Set<String> maskHeaders;

    private final Function<String, String> mask;

    TextHeadersSanitizer(Set<String> maskHeaders, Function<String, String> mask) {
        this.maskHeaders = maskHeaders;
        this.mask = mask;
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

        for (Map.Entry<AsciiString, String> e : headers) {
            final String header = e.getKey().toString();
            if (maskHeaders.contains(header)) {
                sb.append(header).append('=').append(mask.apply(e.getValue())).append(", ");
            } else {
                sb.append(header).append('=').append(e.getValue()).append(", ");
            }
        }

        sb.setCharAt(sb.length() - 2, ']');
        return sb.substring(0, sb.length() - 1);
    }
}
