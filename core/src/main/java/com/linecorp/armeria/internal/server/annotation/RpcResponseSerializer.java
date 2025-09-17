/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.server.annotation;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import com.linecorp.armeria.common.RpcResponse;

final class RpcResponseSerializer extends JsonSerializer<RpcResponse> {

    static final RpcResponseSerializer INSTANCE = new RpcResponseSerializer();
    private static final Object DEFAULT = new Object();

    @Override
    public Class<RpcResponse> handledType() {
        return RpcResponse.class;
    }

    @Override
    public void serialize(RpcResponse value, JsonGenerator jsonGenerator, SerializerProvider serializers)
            throws IOException {
        jsonGenerator.writeStartObject();
        final Object res = value.getNow(DEFAULT);
        jsonGenerator.writeObjectField("res", res);
        jsonGenerator.writeEndObject();
    }
}
