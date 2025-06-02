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

import java.util.Map;
import java.util.stream.Stream;

import org.apache.thrift.TBase;
import org.apache.thrift.TSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;
import com.linecorp.armeria.internal.server.thrift.ThriftVersionDetector;

import testing.thrift.main.FooEnum;
import testing.thrift.main.FooStruct;
import testing.thrift.main.FooUnion;
import testing.thrift.main.InnerFooStruct;
import testing.thrift.main.NormalFooStruct;
import testing.thrift.main.OptionalFooStruct;
import testing.thrift.main.SecretStruct;
import testing.thrift.main.TypedefedFooStruct;

class UnmaskingTest {

    public static Stream<Arguments> jsonProtocolComparison_args() {
        return Stream.of(
                Arguments.of(ThriftMaskingStructs.fooStruct()), Arguments.of(new FooStruct()),
                Arguments.of(ThriftMaskingStructs.optionalFooStruct()), Arguments.of(new OptionalFooStruct()),
                Arguments.of(ThriftMaskingStructs.requiredFooStruct()));
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

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = tBase.deepCopy();
        copied.clear();
        deserializer.fromString(copied, upstreamSer);
        assertThat(copied).isEqualTo(tBase);
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
        final TSerializer tSerializer = new TSerializer(ThriftProtocolFactories.json());
        final TBaseSelectorCache cache = new TBaseSelectorCache(ImmutableList.of());
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String upstreamSer = tSerializer.toString(tBase);
        final String ser = serializer.toString(tBase);
        assertThatJson(ser).isEqualTo(upstreamSer);
    }

    @Test
    void unmaskTBase() throws Exception {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final SecretStruct struct = new SecretStruct().setInnerFooStruct(inner);
        final String fieldName = "innerFooStruct";
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (fieldName.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(InnerFooStruct.class,
                                                     ignored -> "encrypted", str -> inner)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node("3.rec").isStringEqualTo("encrypted");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }

    private static Stream<Arguments> unmaskComplexTBase_args() {
        if (ThriftVersionDetector.minorVersion() >= 19) {
            return Stream.of(Arguments.of(new SecretStruct().setFooStruct(ThriftMaskingStructs.fooStruct()),
                                          "fooStruct", "11.rec"),
                             Arguments.of(new SecretStruct().setTypedefedFooStruct(typedefedFooStruct()),
                                          "typedefedFooStruct", "12.rec"));
        } else {
            return Stream.of(Arguments.of(new SecretStruct().setFooStruct(ThriftMaskingStructs.fooStruct()),
                                          "fooStruct", "11.rec"));
        }
    }

    @ParameterizedTest
    @MethodSource("unmaskComplexTBase_args")
    void unmaskComplexTBase(SecretStruct struct, String name, String expectedPath) throws Exception {
        final String encrypted = "encrypted";
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (name.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(NormalFooStruct.class,
                                                     ignored -> encrypted,
                                                     str -> {
                                                         assertThat(str).isEqualTo(encrypted);
                                                         return struct.fooStruct;
                                                     })
                                          .addMasker(TypedefedFooStruct.class,
                                                     ignored -> encrypted,
                                                     str -> {
                                                         assertThat(str).isEqualTo(encrypted);
                                                         return struct.typedefedFooStruct;
                                                     })
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(expectedPath).isEqualTo(encrypted);

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }

    @Test
    void unmaskString() throws Exception {
        final String hello = "hello";
        final SecretStruct struct = new SecretStruct().setHello(hello);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if (hello.equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class,
                                                     ignored -> "world", str -> hello)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node("1.str").isStringEqualTo("world");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }

    @Test
    void unmaskInt() throws Exception {
        final SecretStruct struct = new SecretStruct().setSecretNum(5);
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("secretNum".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(Integer.class, Object::toString, Integer::valueOf)
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node("13.i32").isStringEqualTo("5");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }

    private static Stream<Arguments> unmaskMap_args() {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final Map<String, InnerFooStruct> innerMap = ImmutableMap.of("key1", inner, "key2", inner);
        if (ThriftVersionDetector.minorVersion() >= 19) {
            return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructMap(innerMap),
                                          "5"),
                             Arguments.of(new SecretStruct().setTypedefedInnerFooStructMap(innerMap),
                                          "6"));
        } else {
            return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructMap(innerMap),
                                          "5"));
        }
    }

    @ParameterizedTest
    @MethodSource("unmaskMap_args")
    void unmaskMapInnerStruct(SecretStruct struct, String name) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> "val2", s -> {
                                              if ("val2".equals(s)) {
                                                  return "val1";
                                              }
                                              return s;
                                          })
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(name + ".map[3].key1.1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(name + ".map[3].key2.1.str").isStringEqualTo("val2");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }

    private static Stream<Arguments> maskList_args() {
        final InnerFooStruct inner = new InnerFooStruct().setStringVal("val1");
        final ImmutableList<InnerFooStruct> innerList = ImmutableList.of(inner, inner, inner);
        if (ThriftVersionDetector.minorVersion() >= 19) {
            return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructList(innerList),
                                          "7"),
                             Arguments.of(new SecretStruct().setTypedefedInnerFooStructList(innerList),
                                          "8"));
        } else {
            return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructList(innerList),
                                          "7"));
        }
    }

    @ParameterizedTest
    @MethodSource("maskList_args")
    void unmaskListElement(SecretStruct struct, String fieldName) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> "val2", s -> {
                                              if ("val2".equals(s)) {
                                                  return "val1";
                                              }
                                              return s;
                                          })
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(fieldName + ".lst").isArray().ofLength(5);
        assertThatJson(ser).node(fieldName + ".lst[2].1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(fieldName + ".lst[3].1.str").isStringEqualTo("val2");
        assertThatJson(ser).node(fieldName + ".lst[4].1.str").isStringEqualTo("val2");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }

    private static Stream<Arguments> maskSet_args() {
        final InnerFooStruct inner1 = new InnerFooStruct().setStringVal("val1");
        final InnerFooStruct inner2 = new InnerFooStruct().setStringVal("val2");
        final ImmutableSet<InnerFooStruct> innerSet = ImmutableSet.of(inner1, inner2);
        if (ThriftVersionDetector.minorVersion() >= 19) {
            return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructSet(innerSet),
                                          "9"),
                             Arguments.of(new SecretStruct().setTypedefedInnerFooStructSet(innerSet),
                                          "10"));
        } else {
            return Stream.of(Arguments.of(new SecretStruct().setInnerFooStructSet(innerSet),
                                          "9"));
        }
    }

    @ParameterizedTest
    @MethodSource("maskSet_args")
    void unmaskSetElement(SecretStruct struct, String fieldName) throws Exception {
        final TBaseSelectorCache cache =
                new TBaseSelectorCache(ImmutableList.of(ThriftFieldMaskerSelector.of(info -> {
                    if ("stringVal".equals(info.fieldMetaData().fieldName)) {
                        return FieldMasker.builder()
                                          .addMasker(String.class, s -> s + '+', s -> {
                                              if (s.endsWith("+")) {
                                                  return s.substring(0, s.length() - 1);
                                              }
                                              return s;
                                          })
                                          .build();
                    }
                    return FieldMasker.noMask();
                })));
        final TMaskingSerializer serializer = new TMaskingSerializer(ThriftProtocolFactories.json(), cache);
        final String ser = serializer.toString(struct);
        assertThatJson(ser).node(fieldName + ".set").isArray().ofLength(4);
        assertThatJson(ser).node(fieldName + ".set[2].1.str").isStringEqualTo("val1+");
        assertThatJson(ser).node(fieldName + ".set[3].1.str").isStringEqualTo("val2+");

        final TMaskingDeserializer deserializer =
                new TMaskingDeserializer(ThriftProtocolFactories.json(), cache);
        final TBase<?, ?> copied = struct.deepCopy();
        copied.clear();
        deserializer.fromString(copied, ser);
        assertThat(copied).isEqualTo(struct);
    }
}
