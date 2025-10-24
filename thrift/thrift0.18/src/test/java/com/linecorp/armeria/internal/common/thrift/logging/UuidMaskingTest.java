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

package com.linecorp.armeria.internal.common.thrift.logging;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;

import testing.thrift.uuid.UuidMessage;

class UuidMaskingTest {

    @Test
    void maskUuidType() throws TException {
        final UuidMessage message = new UuidMessage().setId(UUID.randomUUID());
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("id".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.nullify();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(message);
        assertThatJson(ser).node("1.uid").isStringEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void unmaskUuid() throws Exception {
        final UUID originalUuid = UUID.randomUUID();
        final UuidMessage message = new UuidMessage().setId(originalUuid);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("id".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                .addMasker(UUID.class, uuid -> "masked", str -> originalUuid)
                                .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(message);
        assertThatJson(ser).node("1.uid").isStringEqualTo("masked");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = message.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(message);
    }
}
