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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link JsonNode}.
 */
public final class JsonHeadersSanitizer implements HeadersSanitizer<JsonNode> {

    static final HeadersSanitizer<JsonNode> INSTANCE = new JsonHeadersSanitizerBuilder().build();
    private final Set<String> maskHeaders;
    private final Function<String, String> mask;
    private final ObjectMapper objectMapper;

    JsonHeadersSanitizer(Set<String> maskHeaders, Function<String, String> mask, ObjectMapper objectMapper) {
        this.maskHeaders = maskHeaders;
        this.mask = mask;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode apply(RequestContext requestContext, HttpHeaders headers) {
        final ObjectNode result = objectMapper.createObjectNode();
        for (Map.Entry<AsciiString, String> e : headers) {
            final String header = e.getKey().toString();
            if (maskHeaders.contains(header)) {
                result.put(header, mask.apply(e.getValue()));
            } else {
                result.put(header, e.getValue());
            }
        }

        return result;
    }
}
