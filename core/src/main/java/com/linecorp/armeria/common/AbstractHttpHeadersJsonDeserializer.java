/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;

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

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * Jackson {@link JsonDeserializer} for {@link HttpHeaders} and its subtypes.
 */
abstract class AbstractHttpHeadersJsonDeserializer<T extends HttpHeaders> extends StdDeserializer<T> {

    private static final long serialVersionUID = 6308506823069217145L;

    /**
     * Creates a new instance.
     */
    AbstractHttpHeadersJsonDeserializer(Class<T> type) {
        super(type);
    }

    @Nullable
    @Override
    public final T deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        final JsonNode tree = p.getCodec().readTree(p);
        if (!tree.isObject()) {
            ctx.reportInputMismatch(HttpHeaders.class, "HTTP headers must be an object.");
            return null;
        }

        final ObjectNode obj = (ObjectNode) tree;
        final HttpHeadersBuilder builder = newBuilder();

        for (final Iterator<Entry<String, JsonNode>> i = obj.fields(); i.hasNext();) {
            final Entry<String, JsonNode> e = i.next();
            final AsciiString name = HttpHeaderNames.of(e.getKey());
            final JsonNode values = e.getValue();
            if (!values.isArray()) {
                // Values is a single item, so add it directly.
                addHeader(ctx, builder, name, values);
            } else {
                final int numValues = values.size();
                for (int j = 0; j < numValues; j++) {
                    final JsonNode v = values.get(j);
                    addHeader(ctx, builder, name, v);
                }
            }
        }

        try {
            @SuppressWarnings("unchecked")
            final T headers = (T) builder.build();
            return headers;
        } catch (IllegalStateException e) {
            return ctx.reportInputMismatch(handledType(),
                                           firstNonNull(e.getMessage(), "Required headers are missing."));
        }
    }

    private void addHeader(DeserializationContext ctx, HttpHeadersBuilder builder,
                           AsciiString name, JsonNode valueNode) throws JsonMappingException {
        if (valueNode.isTextual()) {
            builder.add(name, valueNode.asText());
        } else {
            ctx.reportInputMismatch(handledType(),
                                    "HTTP header '%s' contains %s (%s); only strings are allowed.",
                                    name, valueNode.getNodeType(), valueNode);
        }
    }

    abstract HttpHeadersBuilder newBuilder();
}
