/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newEnumInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newExceptionInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newFieldInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newListInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newMapInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newServiceInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newSetInfo;
import static com.linecorp.armeria.server.thrift.ThriftServiceSpecificationGenerator.newStructInfo;
import static java.util.Collections.emptyMap;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.FunctionInfo;
import com.linecorp.armeria.server.docs.ListInfo;
import com.linecorp.armeria.server.docs.MapInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.ServiceSpecificationGenerator;
import com.linecorp.armeria.server.docs.SetInfo;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeInfo;
import com.linecorp.armeria.service.test.thrift.main.FooEnum;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.FooService.bar3_args;
import com.linecorp.armeria.service.test.thrift.main.FooServiceException;
import com.linecorp.armeria.service.test.thrift.main.FooStruct;
import com.linecorp.armeria.service.test.thrift.main.FooUnion;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

public class ThriftServiceSpecificationGeneratorTest {

    private final ServiceSpecificationGenerator generator = new ThriftServiceSpecificationGenerator();

    @Test
    public void servicesTest() throws Exception {
        final ServiceSpecification specification = generator.generate(ImmutableList.of(
                new ServiceConfig(
                        new VirtualHostBuilder().build(),
                        PathMapping.ofExact("/hello"),
                        THttpService.of(mock(HelloService.AsyncIface.class))),
                new ServiceConfig(
                        new VirtualHostBuilder().build(),
                        PathMapping.ofExact("/foo"),
                        THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                               SerializationFormat.THRIFT_COMPACT))));

        final Map<String, ServiceInfo> services = specification.services();
        assertThat(services).containsOnlyKeys(HelloService.class.getName(),
                                              FooService.class.getName());

        assertThat(services.get(HelloService.class.getName()).endpoints())
                .containsExactly(new EndpointInfo("*", "/hello", "", SerializationFormat.THRIFT_BINARY,
                                                  SerializationFormat.ofThrift()));

        assertThat(services.containsKey(FooService.class.getName())).isTrue();
        assertThat(services.get(FooService.class.getName()).endpoints())
                .containsExactly(new EndpointInfo("*", "/foo", "", SerializationFormat.THRIFT_COMPACT,
                                                  EnumSet.of(SerializationFormat.THRIFT_COMPACT)));
    }

    @Test
    public void testNewEnumInfo() throws Exception {
        final EnumInfo enumInfo = newEnumInfo(new EnumMetaData(TType.ENUM, FooEnum.class), emptyMap());

        assertThat(enumInfo).isEqualTo(new EnumInfo(FooEnum.class.getName(),
                                                    Arrays.asList(FooEnum.VAL1, FooEnum.VAL2, FooEnum.VAL3)));
    }

    @Test
    public void testNewExceptionInfo() throws Exception {
        final ExceptionInfo exception = newExceptionInfo(FooServiceException.class, emptyMap());

        assertThat(exception).isEqualTo(new ExceptionInfo(
                FooServiceException.class.getName(),
                ImmutableList.of(newFieldInfo(
                        new FieldMetaData("stringVal", TFieldRequirementType.DEFAULT,
                                          new FieldValueMetaData(TType.STRING, false)),
                        FooServiceException.class.getName(), emptyMap()))));
    }

    @Test
    public void testNewListInfo() throws Exception {
        final ListInfo list = newListInfo(
                new ListMetaData(TType.LIST, new FieldValueMetaData(TType.STRING, false)), emptyMap());

        assertThat(list).isEqualTo(new ListInfo(TypeInfo.STRING));
    }

    @Test
    public void testNewMapInfo() throws Exception {
        final MapInfo map = newMapInfo(new MapMetaData(TType.MAP,
                                                       new FieldValueMetaData(TType.I32, false),
                                                       new FieldValueMetaData(TType.STRING, false)),
                                       emptyMap());

        assertThat(map).isEqualTo(new MapInfo(TypeInfo.I32, TypeInfo.STRING));
    }

    @Test
    public void testNewServiceInfo() throws Exception {
        final ServiceInfo service =
                newServiceInfo(FooService.class,
                               Arrays.asList(
                                       new EndpointInfo("*", "/foo", "a", SerializationFormat.THRIFT_BINARY,
                                                        EnumSet.of(SerializationFormat.THRIFT_BINARY)),
                                       new EndpointInfo("*", "/debug/foo", "b", SerializationFormat.THRIFT_TEXT,
                                                        EnumSet.of(SerializationFormat.THRIFT_TEXT))),
                               ImmutableMap.of(bar3_args.class, new bar3_args().setIntVal(10)),
                               ImmutableMap.of("foobar", "barbaz"));

        assertThat(service.endpoints()).hasSize(2);
        // Should be sorted alphabetically
        assertThat(service.endpoints()).containsExactlyInAnyOrder(
                new EndpointInfo("*", "/debug/foo", "b", SerializationFormat.THRIFT_TEXT,
                                 EnumSet.of(SerializationFormat.THRIFT_TEXT)),
                new EndpointInfo("*", "/foo", "a", SerializationFormat.THRIFT_BINARY,
                                 EnumSet.of(SerializationFormat.THRIFT_BINARY)));

        final Map<String, FunctionInfo> functions = service.functions();
        assertThat(functions).hasSize(5);

        final FunctionInfo bar1 = functions.get("bar1");
        assertThat(bar1.parameters()).isEmpty();
        assertThat(bar1.returnTypeInfo()).isEqualTo(TypeInfo.VOID);
        assertThat(bar1.exceptions()).hasSize(1);
        assertThat(bar1.sampleJsonRequest()).isEmpty();

        final TypeInfo string = TypeInfo.STRING;
        final FunctionInfo bar2 = functions.get("bar2");
        assertThat(bar2.parameters()).isEmpty();
        assertThat(bar2.returnTypeInfo()).isEqualTo(string);
        assertThat(bar2.exceptions()).hasSize(1);
        assertThat(bar2.sampleJsonRequest()).isEmpty();

        final StructInfo foo = newStructInfo(new StructMetaData(TType.STRUCT, FooStruct.class), emptyMap());
        final FunctionInfo bar3 = functions.get("bar3");
        assertThat(bar3.parameters()).containsExactly(
                new FieldInfo("intVal", FieldRequirement.DEFAULT, TypeInfo.I32),
                new FieldInfo("foo", FieldRequirement.DEFAULT, foo));
        assertThat(bar3.returnTypeInfo()).isEqualTo(foo);
        assertThat(bar3.exceptions()).hasSize(1);
        assertThatJson(bar3.sampleJsonRequest()).isEqualTo("{\"intVal\": 10}");

        final FunctionInfo bar4 = functions.get("bar4");
        assertThat(bar4.parameters()).containsExactly(
                new FieldInfo("foos", FieldRequirement.DEFAULT, new ListInfo(foo)));
        assertThat(bar4.returnTypeInfo()).isEqualTo(new ListInfo(foo));
        assertThat(bar4.exceptions()).hasSize(1);
        assertThat(bar4.sampleJsonRequest()).isEmpty();

        final FunctionInfo bar5 = functions.get("bar5");
        assertThat(bar5.parameters()).containsExactly(
                new FieldInfo("foos", FieldRequirement.DEFAULT, new MapInfo(string, foo)));
        assertThat(bar5.returnTypeInfo()).isEqualTo(new MapInfo(string, foo));
        assertThat(bar5.exceptions()).hasSize(1);
        assertThat(bar5.sampleJsonRequest()).isEmpty();

        final String sampleHttpHeaders = service.sampleHttpHeaders();
        assertThat(sampleHttpHeaders).isNotNull();
        assertThatJson(sampleHttpHeaders).isEqualTo("{ \"foobar\": \"barbaz\" }");
    }

    @Test
    public void testNewSetInfoTest() throws Exception {
        final SetInfo set = newSetInfo(
                new SetMetaData(TType.SET, new FieldValueMetaData(TType.I64, false)), emptyMap());

        assertThat(set).isEqualTo(new SetInfo(TypeInfo.I64));
    }

    @Test
    public void testNewStructInfoTest() throws Exception {
        final EnumInfo fooEnum = newEnumInfo(new EnumMetaData(TType.ENUM, FooEnum.class), emptyMap());
        final StructInfo union = newStructInfo(new StructMetaData(TType.STRUCT, FooUnion.class), emptyMap());

        final List<FieldInfo> fields = new ArrayList<>();
        fields.add(new FieldInfo("boolVal", FieldRequirement.DEFAULT, TypeInfo.BOOL));
        fields.add(new FieldInfo("byteVal", FieldRequirement.DEFAULT, TypeInfo.I8));
        fields.add(new FieldInfo("i16Val", FieldRequirement.DEFAULT, TypeInfo.I16));
        fields.add(new FieldInfo("i32Val", FieldRequirement.DEFAULT, TypeInfo.I32));
        fields.add(new FieldInfo("i64Val", FieldRequirement.DEFAULT, TypeInfo.I64));
        fields.add(new FieldInfo("doubleVal", FieldRequirement.DEFAULT, TypeInfo.DOUBLE));
        fields.add(new FieldInfo("stringVal", FieldRequirement.DEFAULT, TypeInfo.STRING));
        fields.add(new FieldInfo("binaryVal", FieldRequirement.DEFAULT, TypeInfo.BINARY));
        fields.add(new FieldInfo("enumVal", FieldRequirement.DEFAULT, fooEnum));
        fields.add(new FieldInfo("unionVal", FieldRequirement.DEFAULT, union));
        fields.add(new FieldInfo("mapVal", FieldRequirement.DEFAULT, new MapInfo(TypeInfo.STRING, fooEnum)));
        fields.add(new FieldInfo("setVal", FieldRequirement.DEFAULT, new SetInfo(union)));
        fields.add(new FieldInfo("listVal", FieldRequirement.DEFAULT, new ListInfo(TypeInfo.STRING)));

        final StructInfo fooStruct = newStructInfo(
                new StructMetaData(TType.STRUCT, FooStruct.class), emptyMap());
        assertThat(fooStruct).isEqualTo(new StructInfo(FooStruct.class.getName(), fields));
    }
}
