/*
 * Copyright 2017 LINE Corporation
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

import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.util.AsciiString;

/**
 * Jackson {@link JsonDeserializer} for {@link HttpHeaders}.
 */
public final class HttpHeadersJsonDeserializer extends StdDeserializer<HttpHeaders> {

    private static final long serialVersionUID = 6308506823069217145L;

    /**
     * Creates a new instance.
     */
    public HttpHeadersJsonDeserializer() {
        super(HttpHeaders.class);
    }

    @Override
    public HttpHeaders deserialize(JsonParser p, DeserializationContext ctx)
            throws IOException {
        final JsonNode tree = p.getCodec().readTree(p);
        if (!tree.isObject()) {
            ctx.reportInputMismatch(HttpHeaders.class, "HTTP headers must be an object.");
            return null;
        }

        final ObjectNode obj = (ObjectNode) tree;
        final HttpHeaders headers = HttpHeaders.of();

        for (Iterator<Entry<String, JsonNode>> i = obj.fields(); i.hasNext();) {
            final Entry<String, JsonNode> e = i.next();
            final AsciiString name = HttpHeaderNames.of(e.getKey());
            final JsonNode values = e.getValue();
            if (!values.isArray()) {
                // Values is a single item, so add it directly.
                addHeader(ctx, headers, name, values);
            } else {
                final int numValues = values.size();
                for (int j = 0; j < numValues; j++) {
                    final JsonNode v = values.get(j);
                    addHeader(ctx, headers, name, v);
                }
            }
        }

        return headers.asImmutable();
    }

    private static void addHeader(DeserializationContext ctx, HttpHeaders headers,
                                  AsciiString name, JsonNode valueNode) throws JsonMappingException {
        if (!valueNode.isTextual()) {
            ctx.reportInputMismatch(HttpHeaders.class,
                                    "HTTP header '%s' contains %s (%s); only strings are allowed.",
                                    name, valueNode.getNodeType(), valueNode);
        }

        headers.add(name, valueNode.asText());
    }
}
