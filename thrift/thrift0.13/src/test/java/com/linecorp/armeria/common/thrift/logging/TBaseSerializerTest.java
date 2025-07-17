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

package com.linecorp.armeria.common.thrift.logging;

import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.fooStruct;
import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.optionalFooStruct;
import static com.linecorp.armeria.internal.common.thrift.logging.ThriftMaskingStructs.requiredFooStruct;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.apache.thrift.TBase;
import org.apache.thrift.TSerializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.logging.ContentSanitizer;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

import testing.thrift.main.FooEnum;
import testing.thrift.main.FooStruct;
import testing.thrift.main.FooUnion;
import testing.thrift.main.InnerFooStruct;
import testing.thrift.main.OptionalFooStruct;
import testing.thrift.main.TypedefedFooStruct;

class TBaseSerializerTest {

    public static Stream<Arguments> jsonProtocolComparison_args() {
        return Stream.of(
                Arguments.of(fooStruct()), Arguments.of(new FooStruct()),
                Arguments.of(optionalFooStruct()), Arguments.of(new OptionalFooStruct()),
                Arguments.of(requiredFooStruct()));
    }

    @ParameterizedTest
    @MethodSource("jsonProtocolComparison_args")
    void jsonProtocolComparison(TBase<?, ?> tBase) throws Exception {
        final ObjectMapper mapper = ContentSanitizer.builder().buildObjectMapper();
        final TSerializer tSerializer = new TSerializer(ThriftProtocolFactories.json());
        final String upstreamSer = tSerializer.toString(tBase);
        final String ser = mapper.writeValueAsString(tBase);
        assertThatJson(ser).isEqualTo(upstreamSer);

        final TBase<?, ?> deser = mapper.reader().readValue(ser, tBase.getClass());
        assertThat(deser).isEqualTo(tBase);
    }

    private static TypedefedFooStruct typedefedFooStruct() {
        return new TypedefedFooStruct().setBoolVal(true)
                                       .setByteVal((byte) 3)
                                       .setI16Val((short) 4)
                                       .setI32Val(5)
                                       .setI64Val(6)
                                       .setDoubleVal(7.2)
                                       .setStringVal("abc")
                                       .setBinaryVal(new byte[] { 1, 2, 3 })
                                       .setEnumVal(FooEnum.VAL2)
                                       .setUnionVal(FooUnion.enumVal(FooEnum.VAL3))
                                       .setMapVal(ImmutableMap.of("1", FooEnum.VAL1, "2", FooEnum.VAL2,
                                                                  "3", FooEnum.VAL3))
                                       .setSetVal(ImmutableSet.of(FooUnion.enumVal(FooEnum.VAL1),
                                                                  FooUnion.stringVal("sval1")))
                                       .setListVal(ImmutableList.of("lval1", "lval2", "lval3"))
                                       .setInnerFooStruct(new InnerFooStruct().setStringVal("innerSval1"));
    }

    public static Stream<Arguments> typedefJsonProtocolComparison_args() {
        return Stream.of(Arguments.of(typedefedFooStruct()), Arguments.of(new TypedefedFooStruct()));
    }

    @ParameterizedTest
    @MethodSource("typedefJsonProtocolComparison_args")
    void typedefJsonProtocolComparison(TBase<?, ?> tBase) throws Exception {
        final ObjectMapper mapper = ContentSanitizer.builder().buildObjectMapper();
        final TSerializer tSerializer = new TSerializer(ThriftProtocolFactories.json());
        final String upstreamSer = tSerializer.toString(tBase);
        final String ser = mapper.writeValueAsString(tBase);
        assertThatJson(ser).isEqualTo(upstreamSer);
        final TBase<?, ?> deser = mapper.reader().readValue(ser, tBase.getClass());
        assertThat(deser).isEqualTo(tBase);
    }
}
