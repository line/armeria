/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.common;

import java.io.IOException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Jackson {@link JsonDeserializer} for {@link MediaType}.
 */
final class MediaTypeJsonDeserializer extends StdDeserializer<MediaType> {

    private static final long serialVersionUID = 2081299438299133097L;

    /**
     * Creates a new instance.
     */
    MediaTypeJsonDeserializer() {
        super(MediaType.class);
    }

    @Nullable
    @Override
    public MediaType deserialize(JsonParser p, DeserializationContext ctx)
            throws IOException {
        final JsonNode tree = p.getCodec().readTree(p);
        if (!tree.isTextual()) {
            ctx.reportInputMismatch(MediaType.class, "media type must be a string.");
            return null;
        }

        final String textValue = tree.textValue();
        try {
            return MediaType.parse(textValue);
        } catch (IllegalArgumentException unused) {
            // Failed to parse.
            ctx.reportInputMismatch(MediaType.class, "malformed media type: " + textValue);
            return null;
        }
    }
}
