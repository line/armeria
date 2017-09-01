/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured.kafka;

import java.util.Map;

import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A Kafka {@link Serializer} which serializes anything which are serializable in JSON format.
 * @param <L> the type of structured log which is being serialized
 */
public class StructuredLogJsonKafkaSerializer<L> implements Serializer<L> {
    private final Serializer<String> stringSerializer = new StringSerializer();
    private final ObjectMapper objectMapper;

    /**
     * Constructs {@link StructuredLogJsonKafkaSerializer}.
     * @param objectMapper an instance of {@link ObjectMapper} which is used to serializer structured logs
     */
    public StructuredLogJsonKafkaSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(Map<String, ?> map, boolean b) { /* noop */ }

    @Override
    public byte[] serialize(String topic, L value) {
        if (value == null) {
            return null;
        }

        try {
            String json = objectMapper.writeValueAsString(value);
            return stringSerializer.serialize(topic, json);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    @Override
    public void close() { /* noop */ }
}
