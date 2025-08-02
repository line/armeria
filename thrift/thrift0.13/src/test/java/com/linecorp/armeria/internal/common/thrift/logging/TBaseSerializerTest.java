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

import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.fooStruct;
import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.optionalFooStruct;
import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.requiredFooStruct;
import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.typedefedFooStruct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.apache.thrift.TBase;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TProtocolFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.logging.ContentSanitizer;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;
import com.linecorp.armeria.common.util.Exceptions;

import testing.thrift.main.FooStruct;
import testing.thrift.main.OptionalFooStruct;
import testing.thrift.main.TypedefedFooStruct;

class TBaseSerializerTest {

    public static Stream<Arguments> protocolComparison_args() {
        final Stream.Builder<Arguments> args = Stream.builder();
        final List<TProtocolFactory> factories =
                ImmutableList.of(ThriftProtocolFactories.json(),
                                 ThriftProtocolFactories.binary(Integer.MAX_VALUE, Integer.MAX_VALUE),
                                 ThriftProtocolFactories.text(),
                                 ThriftProtocolFactories.compact(Integer.MAX_VALUE, Integer.MAX_VALUE));
        final List<TBase<?, ?>> structs =
                ImmutableList.of(fooStruct(), new FooStruct(),
                                 optionalFooStruct(), new OptionalFooStruct(), requiredFooStruct(),
                                 typedefedFooStruct(), new TypedefedFooStruct());
        for (final TProtocolFactory factory : factories) {
            for (final TBase<?, ?> struct : structs) {
                args.add(Arguments.of(factory, struct));
            }
        }
        return args.build();
    }

    @ParameterizedTest
    @MethodSource("protocolComparison_args")
    void protocolComparison(TProtocolFactory factory, TBase<?, ?> tBase) throws Exception {
        final TMaskingSerializer maskingSerializer =
                new TMaskingSerializer(factory, new TBaseSelectorCache(ImmutableList.of()));
        final TSerializer tSerializer = new TSerializer(factory);
        final String upstreamSer = tSerializer.toString(tBase);
        final String ser = maskingSerializer.toString(tBase);
        assertThat(ser).isEqualTo(upstreamSer);
    }

    @Test
    void serializationFailure() throws Exception {
        final TProtocolException tException = new TProtocolException("test");
        final ObjectMapper om = ContentSanitizer.builder().fieldMaskerSelector(
                ThriftFieldMaskerSelector.of(info -> Exceptions.throwUnsafely(tException))).buildObjectMapper();
        assertThatThrownBy(() -> om.writeValueAsString(fooStruct()))
                .isInstanceOf(JsonMappingException.class)
                .cause()
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("Failed to serialize TBase")
                .hasCause(tException);
    }
}
