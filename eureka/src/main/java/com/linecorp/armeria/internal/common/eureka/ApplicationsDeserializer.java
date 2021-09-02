/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.eureka;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import com.linecorp.armeria.common.annotation.Nullable;

final class ApplicationsDeserializer extends StdDeserializer<Applications> {

    private static final long serialVersionUID = -2925089225769559114L;

    private static final String APPS_HASHCODE = "apps_hashcode";
    private static final String APPLICATION = "application";

    private static final ObjectReader readerForApplication =
            new ObjectMapper().setSerializationInclusion(Include.NON_NULL).readerFor(Application.class);

    ApplicationsDeserializer() {
        super(Applications.class);
    }

    @Override
    public Applications deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken jsonToken;
        @Nullable String appsHashCode = null;
        final Set<Application> applications = new HashSet<>();
        while ((jsonToken = p.nextToken()) != JsonToken.END_OBJECT) {
            if (JsonToken.FIELD_NAME == jsonToken) {
                final String fieldName = p.getCurrentName();
                jsonToken = p.nextToken();

                if (APPS_HASHCODE.equals(fieldName)) {
                    appsHashCode = p.getValueAsString();
                } else if (APPLICATION.equals(fieldName)) {
                    if (jsonToken == JsonToken.START_ARRAY) {
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            applications.add(readerForApplication.readValue(p));
                        }
                    } else if (jsonToken == JsonToken.START_OBJECT) {
                        applications.add(readerForApplication.readValue(p));
                    }
                }
            }
        }
        return new Applications(appsHashCode, applications);
    }
}
