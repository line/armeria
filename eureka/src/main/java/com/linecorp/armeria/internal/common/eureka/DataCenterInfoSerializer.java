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
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

final class DataCenterInfoSerializer extends StdSerializer<DataCenterInfo> {

    private static final long serialVersionUID = -5766818057428683420L;

    DataCenterInfoSerializer() {
        super(DataCenterInfo.class);
    }

    @Override
    public void serialize(DataCenterInfo value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();
        final boolean isAmazon = "Amazon".equalsIgnoreCase(value.getName());
        if (isAmazon) {
            gen.writeStringField("@class", "com.netflix.appinfo.AmazonInfo");
        } else {
            gen.writeStringField("@class", "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo");
        }
        gen.writeStringField("name", value.getName());

        if (isAmazon) {
            final Map<String, String> metadata = value.getMetadata();
            gen.writeFieldName("metadata");
            gen.writeStartObject();
            if (!metadata.isEmpty()) {
                for (Entry<String, String> entry : metadata.entrySet()) {
                    gen.writeStringField(entry.getKey(), entry.getValue());
                }
            }
            gen.writeEndObject(); // end for metadata
        }
        gen.writeEndObject(); // end for DataCenter
    }
}
