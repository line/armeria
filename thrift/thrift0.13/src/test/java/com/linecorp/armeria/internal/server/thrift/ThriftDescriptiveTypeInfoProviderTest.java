/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import static com.linecorp.armeria.internal.server.thrift.ThriftDescriptiveTypeInfoProvider.newEnumInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDescriptiveTypeInfoProvider.newExceptionInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDescriptiveTypeInfoProvider.newFieldInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDescriptiveTypeInfoProvider.newStructInfo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

import testing.thrift.main.FooEnum;
import testing.thrift.main.FooServiceException;
import testing.thrift.main.FooStruct;
import testing.thrift.main.FooUnion;
import testing.thrift.main.InnerFooStruct;

class ThriftDescriptiveTypeInfoProviderTest {

    @Test
    void testNewEnumInfo() {
        final EnumInfo enumInfo = newEnumInfo(FooEnum.class);

        assertThat(enumInfo).isEqualTo(
                new EnumInfo(FooEnum.class.getName(),
                             ImmutableList.of(new EnumValueInfo("VAL1", 1),
                                              new EnumValueInfo("VAL2", 2),
                                              new EnumValueInfo("VAL3", 3))));
    }

    @Test
    void testNewExceptionInfo() {
        final ExceptionInfo exception = newExceptionInfo(FooServiceException.class);

        assertThat(exception).isEqualTo(new ExceptionInfo(
                FooServiceException.class.getName(),
                ImmutableList.of(newFieldInfo(
                        FooServiceException.class,
                        new FieldMetaData("stringVal", TFieldRequirementType.DEFAULT,
                                          new FieldValueMetaData(TType.STRING, false))))));
    }

    @Test
    void testNewStructInfoTest() throws Exception {
        final TypeSignature string = TypeSignature.ofBase("string");
        final List<FieldInfo> fields = new ArrayList<>();
        fields.add(FieldInfo.of("boolVal", TypeSignature.ofBase("bool")));
        fields.add(FieldInfo.of("byteVal", TypeSignature.ofBase("i8")));
        fields.add(FieldInfo.of("i16Val", TypeSignature.ofBase("i16")));
        fields.add(FieldInfo.of("i32Val", TypeSignature.ofBase("i32")));
        fields.add(FieldInfo.of("i64Val", TypeSignature.ofBase("i64")));
        fields.add(FieldInfo.of("doubleVal", TypeSignature.ofBase("double")));
        fields.add(FieldInfo.of("stringVal", string));
        fields.add(FieldInfo.of("binaryVal", TypeSignature.ofBase("binary")));
        fields.add(FieldInfo.of("enumVal", TypeSignature.ofEnum(FooEnum.class)));
        fields.add(FieldInfo.of("unionVal", TypeSignature.ofStruct(FooUnion.class)));
        fields.add(FieldInfo.of("mapVal", TypeSignature.ofMap(
                string, TypeSignature.ofEnum(FooEnum.class))));
        fields.add(FieldInfo.of("setVal", TypeSignature.ofSet(TypeSignature.ofStruct(FooUnion.class))));
        fields.add(FieldInfo.of("listVal", TypeSignature.ofList(string)));
        fields.add(FieldInfo.builder("selfRef", TypeSignature.ofStruct(FooStruct.class))
                            .requirement(FieldRequirement.OPTIONAL).build());
        fields.add(FieldInfo.builder("innerFooStruct", TypeSignature.ofStruct(InnerFooStruct.class)).build());

        final StructInfo fooStruct = newStructInfo(FooStruct.class);
        assertThat(fooStruct).isEqualTo(new StructInfo(FooStruct.class.getName(), fields));
    }
}
