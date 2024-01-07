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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link JsonNode}.
 */
public final class JsonHeadersSanitizer implements HeadersSanitizer<JsonNode> {

    static final HeadersSanitizer<JsonNode> INSTANCE = new JsonHeadersSanitizerBuilder().build();
    private final Set<CharSequence> maskingHeaders;
    private final Function<String, String> maskingFunction;
    private final ObjectMapper objectMapper;

    JsonHeadersSanitizer(Set<CharSequence> maskingHeaders, Function<String, String> maskingFunction,
                         ObjectMapper objectMapper) {
        this.maskingHeaders = maskingHeaders;
        this.maskingFunction = maskingFunction;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode apply(RequestContext requestContext, HttpHeaders headers) {
        final ObjectNode result = objectMapper.createObjectNode();
        final Map<String, List<String>> headersWithValuesAsList = new LinkedHashMap<>();
        for (Map.Entry<AsciiString, String> entry : headers) {
            final String header = entry.getKey().toString().toLowerCase();
            final String value = maskingHeaders.contains(header) ? maskingFunction.apply(entry.getValue())
                                                                 : entry.getValue();
            headersWithValuesAsList.computeIfAbsent(header, k -> new ArrayList<>()).add(value);
        }

        final Set<Entry<String, List<String>>> entries = headersWithValuesAsList.entrySet();
        for (Map.Entry<String, List<String>> entry : entries) {
            final String header = entry.getKey();
            final List<String> values = entry.getValue();

            result.put(header, values.size() > 1 ? values.toString() : values.get(0));
        }

        return result;
    }
}
