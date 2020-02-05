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
package com.linecorp.armeria.server.docs;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson {@link JsonSerializer} for {@link TypeSignature}.
 */
final class TypeSignatureJsonSerializer extends StdSerializer<TypeSignature> {

    private static final long serialVersionUID = 5186823627317402798L;

    /**
     * Creates a new instance.
     */
    TypeSignatureJsonSerializer() {
        super(TypeSignature.class);
    }

    @Override
    public void serialize(TypeSignature typeSignature, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {

        gen.writeString(typeSignature.signature());
    }
}
