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

import static com.linecorp.armeria.common.logging.TextHeadersSanitizer.maskHeaders;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link JsonNode}.
 */
final class JsonHeadersSanitizer implements HeadersSanitizer<JsonNode> {

    static final HeadersSanitizer<JsonNode> INSTANCE = new JsonHeadersSanitizerBuilder().build();
    private final Set<AsciiString> sensitiveHeaders;
    private final HeaderMaskingFunction maskingFunction;
    private final ObjectMapper objectMapper;

    JsonHeadersSanitizer(Set<AsciiString> sensitiveHeaders, HeaderMaskingFunction maskingFunction,
                         ObjectMapper objectMapper) {
        this.sensitiveHeaders = sensitiveHeaders;
        this.maskingFunction = maskingFunction;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode sanitize(RequestContext requestContext, HttpHeaders headers) {
        final ObjectNode result = objectMapper.createObjectNode();
        maskHeaders(headers, sensitiveHeaders, maskingFunction,
                    (header, values) -> result.put(header.toString(), values.size() > 1 ?
                                                                      values.toString() : values.get(0)));

        return result;
    }
}
