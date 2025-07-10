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
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.util.Map;
import java.util.stream.Stream;

import org.apache.thrift.TBase;
import org.apache.thrift.TSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;

import testing.thrift.main.FooEnum;
import testing.thrift.main.FooStruct;
import testing.thrift.main.FooUnion;
import testing.thrift.main.InnerFooStruct;
import testing.thrift.main.OptionalFooStruct;
import testing.thrift.main.SecretStruct;
import testing.thrift.main.TypedefedFooStruct;

class MaskingTest {

    public static Stream<Arguments> jsonProtocolComparison_args() {
        return Stream.of(
                Arguments.of(fooStruct()), Arguments.of(new FooStruct()),
                Arguments.of(optionalFooStruct()), Arguments.of(new OptionalFooStruct()),
                Arguments.of(requiredFooStruct()));
    }

    @ParameterizedTest
    @MethodSource("jsonProtocolComparison_args")
    void delegatingProtocol(TBase<?, ?> tBase) throws Exception {
        final TSerializer tSerializer = new TSerializer(ThriftProtocolFactories.json());
        final TBaseSelectorCache cache = new TBaseSelectorCache(ImmutableList.of());
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String upstreamSer = tSerializer.toString(tBase);
        final String ser = serializer.toString(tBase);
        assertThatJson(ser).isEqualTo(upstreamSer);
    }

    @Test
    void maskPrimitiveToNull() throws Exception {
        final SecretStruct struct = new SecretStruct().setHello("hello")
                                                      .setSecret("secret");
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("secret".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.nullify();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node("1.str").isStringEqualTo("hello")
                           .node("2.str").isStringEqualTo("");
    }

    @ParameterizedTest
    @CsvSource({ "innerFooStruct,3", "typedefedInnerFooStruct,4"})
    void maskTbaseToTbase(String fieldName, String jsonNode) throws Exception {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final SecretStruct struct = new SecretStruct().setInnerFooStruct(inner)
                                                      .setTypedefedInnerFooStruct(inner);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(InnerFooStruct.class,
                                                     s -> s.deepCopy().setStringVal("val2"))
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode + ".rec.1.str").isStringEqualTo("val2");
    }

    @ParameterizedTest
    @CsvSource({"innerFooStruct,3.rec", "typedefedInnerFooStruct,4.rec"})
    void maskTbaseToNull(String fieldName, String jsonNode) throws Exception {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final SecretStruct struct = new SecretStruct().setInnerFooStruct(inner)
                                                      .setTypedefedInnerFooStruct(inner);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.nullify();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode).isEqualTo("{}");
    }

    private static Stream<Arguments> maskMap_args() {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final Map<String, InnerFooStruct> innerMap = ImmutableMap.of("key1", inner, "key2", inner);
        return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructMap(innerMap),
                                      "innerFooStructMap", "5.map"),
                         Arguments.of(new SecretStruct().setTypedefedInnerFooStructMap(innerMap),
                                      "typedefedInnerFooStructMap", "6.map"));
    }

    @ParameterizedTest
    @MethodSource("maskMap_args")
    void maskMapInnerStruct(SecretStruct struct, String name, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> "val2")
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode + "[3].key1.1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(jsonNode + "[3].key2.1.str").isStringEqualTo("val2");
    }

    @ParameterizedTest
    @MethodSource("maskMap_args")
    void maskMapToNull(SecretStruct struct, String name, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (name.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.nullify();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode + "[2]").isEqualTo(0);
    }

    private static Stream<Arguments> maskList_args() {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final ImmutableList<InnerFooStruct> innerList = ImmutableList.of(inner, inner, inner);
        return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructList(innerList),
                                      "innerFooStructList", "7.lst"),
                         Arguments.of(new SecretStruct().setTypedefedInnerFooStructList(innerList),
                                      "typedefedInnerFooStructList", "8.lst"));
    }

    @ParameterizedTest
    @MethodSource("maskList_args")
    void maskListElement(SecretStruct struct, String fieldName, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> "val2")
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode).isArray().ofLength(5);
        assertThatJson(ser).node(jsonNode + "[2].1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(jsonNode + "[3].1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(jsonNode + "[4].1.str").isStringEqualTo("val2");
    }

    @ParameterizedTest
    @MethodSource("maskList_args")
    void maskListToNull(SecretStruct struct, String fieldName, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.nullify();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode + "[1]").isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("maskList_args")
    void maskListElementToNull(SecretStruct struct, String fieldName, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(InnerFooStruct.class, ignored -> null)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode).isArray().ofLength(5);
        assertThatJson(ser).node(jsonNode + "[2]").isEqualTo("{}");
        assertThatJson(ser).node(jsonNode + "[3]").isEqualTo("{}");
        assertThatJson(ser).node(jsonNode + "[4]").isEqualTo("{}");
    }

    private static Stream<Arguments> maskSet_args() {
        final InnerFooStruct inner1 = new InnerFooStruct().setStringVal("val1");
        final InnerFooStruct inner2 = new InnerFooStruct().setStringVal("val2");
        final ImmutableSet<InnerFooStruct> innerSet = ImmutableSet.of(inner1, inner2);
        return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructSet(innerSet),
                                      "innerFooStructSet", "9.set"),
                         Arguments.of(new SecretStruct().setTypedefedInnerFooStructSet(innerSet),
                                      "typedefedInnerFooStructSet", "10.set"));
    }

    @ParameterizedTest
    @MethodSource("maskSet_args")
    void maskSetElement(SecretStruct struct, String fieldName, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> "val2")
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode).isArray().ofLength(4);
        assertThatJson(ser).node(jsonNode + "[2].1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(jsonNode + "[3].1.str").isStringEqualTo("val2");
    }

    @ParameterizedTest
    @MethodSource("maskSet_args")
    void maskSetToNull(SecretStruct struct, String fieldName, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.nullify();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode + "[1]").isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("maskSet_args")
    void maskSetElementToNull(SecretStruct struct, String fieldName, String jsonNode) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(InnerFooStruct.class, ignored -> null)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode).isArray().ofLength(4);
        assertThatJson(ser).node(jsonNode + "[2]").isEqualTo("{}");
        assertThatJson(ser).node(jsonNode + "[3]").isEqualTo("{}");
    }

    static TypedefedFooStruct typedefedFooStruct() {
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

    static Stream<Arguments> typedefJsonProtocolComparison_args() {
        return Stream.of(Arguments.of(typedefedFooStruct()), Arguments.of(new TypedefedFooStruct()));
    }

    @ParameterizedTest
    @MethodSource("typedefJsonProtocolComparison_args")
    void typedefJsonProtocolComparison(TBase<?, ?> tBase) throws Exception {
        final TSerializer tSerializer = new TSerializer(ThriftProtocolFactories.json());
        final TBaseSelectorCache cache = new TBaseSelectorCache(ImmutableList.of());
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String upstreamSer = tSerializer.toString(tBase);
        final String ser = serializer.toString(tBase);
        assertThatJson(ser).isEqualTo(upstreamSer);
    }
}
