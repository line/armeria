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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import testing.thrift.main.FooEnum;
import testing.thrift.main.FooUnion;
import testing.thrift.main.InnerFooStruct;
import testing.thrift.main.NormalFooStruct;
import testing.thrift.main.OptionalFooStruct;
import testing.thrift.main.RequiredFooStruct;

public final class ThriftMaskingStructs {

    public static NormalFooStruct fooStruct() {
        return new NormalFooStruct().setBoolVal(true)
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
                                    .setInnerFooStruct(new InnerFooStruct("innerSval1"))
                                    .setListOfLists(ImmutableList.of(
                                            ImmutableList.of(new InnerFooStruct("lval1"),
                                                             new InnerFooStruct("lval2")),
                                            ImmutableList.of(new InnerFooStruct("lval3"),
                                                             new InnerFooStruct("lval4"))))
                                    .setSetOfSets(ImmutableSet.of(
                                            ImmutableSet.of(new InnerFooStruct("lval1"),
                                                            new InnerFooStruct("lval2")),
                                            ImmutableSet.of(new InnerFooStruct("lval3"),
                                                            new InnerFooStruct("lval4"))))
                                    .setMapOfMaps(ImmutableMap.of(
                                            "key1",
                                            ImmutableMap.of("key1.1", new InnerFooStruct("lval1"),
                                                            "key1.2", new InnerFooStruct("lval2")),
                                            "key2",
                                            ImmutableMap.of("key1.1", new InnerFooStruct("lval1"),
                                                            "key1.2", new InnerFooStruct("lval2"))));
    }

    public static OptionalFooStruct optionalFooStruct() {
        return new OptionalFooStruct().setBoolVal(true)
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
                                      .setInnerFooStruct(new InnerFooStruct().setStringVal("innerSval1"))
                                      .setListOfLists(ImmutableList.of(
                                              ImmutableList.of(new InnerFooStruct("lval1"),
                                                               new InnerFooStruct("lval2")),
                                              ImmutableList.of(new InnerFooStruct("lval3"),
                                                               new InnerFooStruct("lval4"))))
                                      .setSetOfSets(ImmutableSet.of(
                                              ImmutableSet.of(new InnerFooStruct("lval1"),
                                                              new InnerFooStruct("lval2")),
                                              ImmutableSet.of(new InnerFooStruct("lval3"),
                                                              new InnerFooStruct("lval4"))))
                                      .setMapOfMaps(ImmutableMap.of(
                                              "key1",
                                              ImmutableMap.of("key1.1", new InnerFooStruct("lval1"),
                                                              "key1.2", new InnerFooStruct("lval2")),
                                              "key2",
                                              ImmutableMap.of("key1.1", new InnerFooStruct("lval1"),
                                                              "key1.2", new InnerFooStruct("lval2"))));
    }

    public static RequiredFooStruct requiredFooStruct() {
        return new RequiredFooStruct().setBoolVal(true)
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
                                      .setInnerFooStruct(new InnerFooStruct().setStringVal("innerSval1"))
                                      .setListOfLists(ImmutableList.of(
                                              ImmutableList.of(new InnerFooStruct("lval1"),
                                                               new InnerFooStruct("lval2")),
                                              ImmutableList.of(new InnerFooStruct("lval3"),
                                                               new InnerFooStruct("lval4"))))
                                      .setSetOfSets(ImmutableSet.of(
                                              ImmutableSet.of(new InnerFooStruct("lval1"),
                                                              new InnerFooStruct("lval2")),
                                              ImmutableSet.of(new InnerFooStruct("lval3"),
                                                              new InnerFooStruct("lval4"))))
                                      .setMapOfMaps(ImmutableMap.of(
                                              "key1",
                                              ImmutableMap.of("key1.1", new InnerFooStruct("lval1"),
                                                              "key1.2", new InnerFooStruct("lval2")),
                                              "key2",
                                              ImmutableMap.of("key1.1", new InnerFooStruct("lval1"),
                                                              "key1.2", new InnerFooStruct("lval2"))));
    }

    private ThriftMaskingStructs() {}
}
