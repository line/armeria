/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.Test;

import com.linecorp.armeria.service.test.thrift.main.FooEnum;
import com.linecorp.armeria.service.test.thrift.main.FooStruct;
import com.linecorp.armeria.service.test.thrift.main.FooUnion;

public class StructInfoTest {

    @Test
    public void testOf() throws Exception {
        final TypeInfo string = TypeInfo.of(ValueType.STRING, false);
        final EnumInfo fooEnum = EnumInfo.of(new EnumMetaData(TType.ENUM, FooEnum.class));
        final StructInfo union = StructInfo.of(new StructMetaData(TType.STRUCT, FooUnion.class));

        final List<FieldInfo> fields = new ArrayList<>();
        fields.add(FieldInfo.of("boolVal", RequirementType.DEFAULT, TypeInfo.of(ValueType.BOOL, false)));
        fields.add(FieldInfo.of("byteVal", RequirementType.DEFAULT, TypeInfo.of(ValueType.BYTE, false)));
        fields.add(FieldInfo.of("i16Val", RequirementType.DEFAULT, TypeInfo.of(ValueType.I16, false)));
        fields.add(FieldInfo.of("i32Val", RequirementType.DEFAULT, TypeInfo.of(ValueType.I32, false)));
        fields.add(FieldInfo.of("i64Val", RequirementType.DEFAULT, TypeInfo.of(ValueType.I64, false)));
        fields.add(FieldInfo.of("doubleVal", RequirementType.DEFAULT, TypeInfo.of(ValueType.DOUBLE, false)));
        fields.add(FieldInfo.of("stringVal", RequirementType.DEFAULT, string));
        fields.add(FieldInfo.of("binaryVal", RequirementType.DEFAULT, TypeInfo.of(ValueType.STRING, true)));
        fields.add(FieldInfo.of("slistVal", RequirementType.DEFAULT, string));
        fields.add(FieldInfo.of("enumVal", RequirementType.DEFAULT, fooEnum));
        fields.add(FieldInfo.of("unionVal", RequirementType.DEFAULT, union));
        fields.add(FieldInfo.of("mapVal", RequirementType.DEFAULT, MapInfo.of(string, fooEnum)));
        fields.add(FieldInfo.of("setVal", RequirementType.DEFAULT, SetInfo.of(union)));
        fields.add(FieldInfo.of("listVal", RequirementType.DEFAULT, ListInfo.of(string)));

        final StructInfo fooStruct = StructInfo.of(new StructMetaData(TType.STRUCT, FooStruct.class));
        assertThat(fooStruct, is(StructInfo.of(FooStruct.class.getName(), fields)));
    }
}
