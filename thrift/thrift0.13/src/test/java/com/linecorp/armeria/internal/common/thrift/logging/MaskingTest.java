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
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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

import testing.thrift.main.InnerFooStruct;
import testing.thrift.main.SecretStruct;

class MaskingTest {

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

    @Test
    void maskPrimitiveToString() throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("boolVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(Boolean.class, b -> "masked", s -> true)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(fooStruct());
        assertThatJson(ser).node("1.tf").isStringEqualTo("masked");
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

    @ParameterizedTest
    @CsvSource({"innerFooStruct,3.rec", "typedefedInnerFooStruct,4.rec"})
    void maskTbaseToString(String fieldName, String jsonNode) throws Exception {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final SecretStruct struct = new SecretStruct().setInnerFooStruct(inner)
                                                      .setTypedefedInnerFooStruct(inner);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(InnerFooStruct.class, f -> "masked", s -> inner)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(jsonNode).isEqualTo("masked");
    }

    @ParameterizedTest
    @CsvSource({"innerFooStruct,3.rec", "typedefedInnerFooStruct,4.rec"})
    void maskTbaseToRandomThrows(String fieldName, String jsonNode) throws Exception {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final SecretStruct struct = new SecretStruct().setInnerFooStruct(inner)
                                                      .setTypedefedInnerFooStruct(inner);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return new FieldMasker() {
                            @Override
                            public Object mask(Object obj) {
                                return new Object();
                            }
                        };
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        assertThatThrownBy(() -> serializer.toString(struct))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the expected mapped class");
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

    @Test
    void maskMapOfMaps() throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> s + '+')
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(fooStruct());
        assertThatJson(ser).node("19.map[2]").isEqualTo(2);
        assertThatJson(ser).node("19.map[3].key1[3].key1\\.1.1.str").isEqualTo("lval1+");
        assertThatJson(ser).node("19.map[3].key1[3].key1\\.2.1.str").isEqualTo("lval2+");
        assertThatJson(ser).node("19.map[3].key2[3].key1\\.1.1.str").isEqualTo("lval1+");
        assertThatJson(ser).node("19.map[3].key2[3].key1\\.2.1.str").isEqualTo("lval2+");
    }

    @Test
    void maskListOfLists() throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> s + '+')
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(fooStruct());
        assertThatJson(ser).node("17.lst[1]").isEqualTo(2);
        assertThatJson(ser).node("17.lst[2].[1]").isEqualTo(2);
        assertThatJson(ser).node("17.lst[2].[2].1.str").isEqualTo("lval1+");
        assertThatJson(ser).node("17.lst[2].[3].1.str").isEqualTo("lval2+");
        assertThatJson(ser).node("17.lst[3].[2].1.str").isEqualTo("lval3+");
        assertThatJson(ser).node("17.lst[3].[3].1.str").isEqualTo("lval4+");
    }

    @Test
    void maskSetOfSets() throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> s + '+')
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(fooStruct());
        assertThatJson(ser).node("18.set[1]").isEqualTo(2);
        assertThatJson(ser).node("18.set[2].[1]").isEqualTo(2);
        assertThatJson(ser).node("18.set[2].[2].1.str").isEqualTo("lval1+");
        assertThatJson(ser).node("18.set[2].[3].1.str").isEqualTo("lval2+");
        assertThatJson(ser).node("18.set[3].[2].1.str").isEqualTo("lval3+");
        assertThatJson(ser).node("18.set[3].[3].1.str").isEqualTo("lval4+");
    }

    private static Stream<Arguments> collectionMutationNotAllowed() {
        return Stream.of(
                Arguments.of(FieldMasker.builder().addMasker(Map.class, m -> {
                    m.put(1, 2);
                    return m;
                }).build()),
                Arguments.of(FieldMasker.builder().addMasker(Map.class, m -> ImmutableMap.of()).build()),
                Arguments.of(FieldMasker.builder().addMasker(List.class, l -> {
                    l.add(1);
                    return l;
                }).build()),
                Arguments.of(FieldMasker.builder().addMasker(List.class, l -> ImmutableList.of()).build()),
                Arguments.of(FieldMasker.builder().addMasker(Set.class, s -> {
                    s.add(1);
                    return s;
                }).build()),
                Arguments.of(FieldMasker.builder().addMasker(Set.class, s -> ImmutableSet.of()).build())
        );
    }

    @ParameterizedTest
    @MethodSource
    void collectionMutationNotAllowed(FieldMasker masker) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> masker)));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        assertThatThrownBy(() -> serializer.serialize(fooStruct()))
                .isInstanceOfAny(UnsupportedOperationException.class, IllegalArgumentException.class);
    }
}
