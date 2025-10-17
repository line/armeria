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

import com.linecorp.armeria.common.RpcRequest;

final class RpcRequestSerializer extends JsonSerializer<RpcRequest> {

    static final RpcRequestSerializer INSTANCE = new RpcRequestSerializer();

    @Override
    public Class<RpcRequest> handledType() {
        return RpcRequest.class;
    }

    @Override
    public void serialize(RpcRequest value, JsonGenerator jsonGenerator, SerializerProvider serializers)
            throws IOException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("service", value.serviceName());
        jsonGenerator.writeStringField("method", value.method());
        jsonGenerator.writeArrayFieldStart("params");
        for (Object param : value.params()) {
            jsonGenerator.writeObject(param);
        }
        jsonGenerator.writeEndArray();
        jsonGenerator.writeEndObject();
    }
}
