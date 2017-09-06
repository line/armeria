/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.newEnumInfo;
import static com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.newExceptionInfo;
import static com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.newFieldInfo;
import static com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.newServiceInfo;
import static com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.newStructInfo;
import static com.linecorp.armeria.server.thrift.ThriftDocServicePlugin.toTypeSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.service.test.thrift.main.FooEnum;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.FooServiceException;
import com.linecorp.armeria.service.test.thrift.main.FooStruct;
import com.linecorp.armeria.service.test.thrift.main.FooUnion;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;

public class ThriftDocServicePluginTest {

    private final DocServicePlugin generator = new ThriftDocServicePlugin();

    @Test
    public void servicesTest() throws Exception {
        final ServiceConfig helloService = new ServiceConfig(
                new VirtualHostBuilder().build(),
                PathMapping.ofExact("/hello"),
                THttpService.of(mock(AsyncIface.class)));

        final ServiceConfig fooService = new ServiceConfig(
                new VirtualHostBuilder().build(),
                PathMapping.ofExact("/foo"),
                THttpService.ofFormats(mock(FooService.AsyncIface.class), ThriftSerializationFormats.COMPACT));

        // Generate the specification with the ServiceConfigs.
        final ServiceSpecification specification =
                generator.generateSpecification(ImmutableSet.of(helloService, fooService));

        // Ensure the specification contains all services.
        final Map<String, ServiceInfo> services =
                specification.services().stream()
                             .collect(toImmutableMap(ServiceInfo::name, Function.identity()));

        assertThat(services).containsOnlyKeys(HelloService.class.getName(), FooService.class.getName());

        // Ensure each service contains all endpoints and example HTTP headers.
        final ServiceInfo helloServiceInfo = services.get(HelloService.class.getName());
        assertThat(helloServiceInfo.exampleHttpHeaders()).isEmpty();

        final ServiceInfo fooServiceInfo = services.get(FooService.class.getName());
        assertThat(fooServiceInfo.exampleHttpHeaders()).isEmpty();

        // Ensure the example request exists as well.
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));

        assertThat(methods).containsKey("bar4");
        final MethodInfo bar4 = methods.get("bar4");
        assertThat(bar4.exampleRequests()).isEmpty();
        assertThat(bar4.endpoints())
                .containsExactly(new EndpointInfo("*", "/foo", "", ThriftSerializationFormats.COMPACT,
                                                  ImmutableSet.of(ThriftSerializationFormats.COMPACT)));
    }

    @Test
    public void testNewEnumInfo() throws Exception {
        final EnumInfo enumInfo = newEnumInfo(FooEnum.class);

        assertThat(enumInfo).isEqualTo(new EnumInfo(FooEnum.class.getName(),
                                                    Arrays.asList(new EnumValueInfo("VAL1"),
                                                                  new EnumValueInfo("VAL2"),
                                                                  new EnumValueInfo("VAL3"))));
    }

    @Test
    public void testNewExceptionInfo() throws Exception {
        final ExceptionInfo exception = newExceptionInfo(FooServiceException.class);

        assertThat(exception).isEqualTo(new ExceptionInfo(
                FooServiceException.class.getName(),
                ImmutableList.of(newFieldInfo(
                        FooServiceException.class,
                        new FieldMetaData("stringVal", TFieldRequirementType.DEFAULT,
                                          new FieldValueMetaData(TType.STRING, false))))));
    }

    @Test
    public void testNewServiceInfo() throws Exception {
        final ServiceInfo service =
                newServiceInfo(FooService.class, ImmutableList.of(
                        new EndpointInfo("*", "/foo", "a", ThriftSerializationFormats.BINARY,
                                         ImmutableSet.of(ThriftSerializationFormats.BINARY)),
                        new EndpointInfo("*", "/debug/foo", "b", ThriftSerializationFormats.TEXT,
                                         ImmutableSet.of(ThriftSerializationFormats.TEXT))));

        final Map<String, MethodInfo> methods =
                service.methods().stream().collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).hasSize(6);

        final MethodInfo bar1 = methods.get("bar1");
        assertThat(bar1.parameters()).isEmpty();
        assertThat(bar1.returnTypeSignature()).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(bar1.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar1.exampleRequests().isEmpty());
        assertThat(bar1.endpoints()).containsExactlyInAnyOrder(
                new EndpointInfo("*", "/debug/foo", "b", ThriftSerializationFormats.TEXT,
                                 ImmutableSet.of(ThriftSerializationFormats.TEXT)),
                new EndpointInfo("*", "/foo", "a", ThriftSerializationFormats.BINARY,
                                 ImmutableSet.of(ThriftSerializationFormats.BINARY)));

        final TypeSignature string = TypeSignature.ofBase("string");
        final MethodInfo bar2 = methods.get("bar2");
        assertThat(bar2.parameters()).isEmpty();
        assertThat(bar2.returnTypeSignature()).isEqualTo(string);
        assertThat(bar2.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar2.exampleRequests()).isEmpty();

        final TypeSignature foo = TypeSignature.ofNamed(FooStruct.class);
        final MethodInfo bar3 = methods.get("bar3");
        assertThat(bar3.parameters()).containsExactly(
                new FieldInfo("intVal", FieldRequirement.DEFAULT, TypeSignature.ofBase("i32")),
                new FieldInfo("foo", FieldRequirement.DEFAULT, foo));
        assertThat(bar3.returnTypeSignature()).isEqualTo(foo);
        assertThat(bar3.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar3.exampleRequests()).isEmpty();

        final MethodInfo bar4 = methods.get("bar4");
        assertThat(bar4.parameters()).containsExactly(
                new FieldInfo("foos", FieldRequirement.DEFAULT, TypeSignature.ofList(foo)));
        assertThat(bar4.returnTypeSignature()).isEqualTo(TypeSignature.ofList(foo));
        assertThat(bar4.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar4.exampleRequests()).isEmpty();

        final MethodInfo bar5 = methods.get("bar5");
        assertThat(bar5.parameters()).containsExactly(
                new FieldInfo("foos", FieldRequirement.DEFAULT, TypeSignature.ofMap(string, foo)));
        assertThat(bar5.returnTypeSignature()).isEqualTo(TypeSignature.ofMap(string, foo));
        assertThat(bar5.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar5.exampleRequests()).isEmpty();

        final MethodInfo bar6 = methods.get("bar6");
        assertThat(bar6.parameters()).containsExactly(
                new FieldInfo("foo1", FieldRequirement.DEFAULT, string),
                new FieldInfo("foo2", FieldRequirement.DEFAULT,
                              TypeSignature.ofUnresolved("TypedefedStruct")),
                new FieldInfo("foo3", FieldRequirement.DEFAULT,
                              TypeSignature.ofUnresolved("TypedefedEnum")),
                new FieldInfo("foo4", FieldRequirement.DEFAULT,
                              TypeSignature.ofUnresolved("TypedefedMap")),
                new FieldInfo("foo5", FieldRequirement.DEFAULT,
                              TypeSignature.ofUnresolved("TypedefedList")),
                new FieldInfo("foo6", FieldRequirement.DEFAULT,
                              TypeSignature.ofUnresolved("TypedefedSet")),
                new FieldInfo("foo7", FieldRequirement.DEFAULT,
                              TypeSignature.ofUnresolved("NestedTypedefedStructs")),
                new FieldInfo("foo8", FieldRequirement.DEFAULT,
                              TypeSignature.ofList(TypeSignature.ofList(
                                      TypeSignature.ofUnresolved("TypedefedStruct")))));

        assertThat(bar6.returnTypeSignature()).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(bar6.exceptionTypeSignatures()).isEmpty();
        assertThat(bar6.exampleRequests()).isEmpty();

        final List<HttpHeaders> exampleHttpHeaders = service.exampleHttpHeaders();
        assertThat(exampleHttpHeaders).isEmpty();
    }

    @Test
    public void testNewStructInfoTest() throws Exception {
        final TypeSignature string = TypeSignature.ofBase("string");
        final List<FieldInfo> fields = new ArrayList<>();
        fields.add(new FieldInfo("boolVal", FieldRequirement.DEFAULT, TypeSignature.ofBase("bool")));
        fields.add(new FieldInfo("byteVal", FieldRequirement.DEFAULT, TypeSignature.ofBase("i8")));
        fields.add(new FieldInfo("i16Val", FieldRequirement.DEFAULT, TypeSignature.ofBase("i16")));
        fields.add(new FieldInfo("i32Val", FieldRequirement.DEFAULT, TypeSignature.ofBase("i32")));
        fields.add(new FieldInfo("i64Val", FieldRequirement.DEFAULT, TypeSignature.ofBase("i64")));
        fields.add(new FieldInfo("doubleVal", FieldRequirement.DEFAULT, TypeSignature.ofBase("double")));
        fields.add(new FieldInfo("stringVal", FieldRequirement.DEFAULT, string));
        fields.add(new FieldInfo("binaryVal", FieldRequirement.DEFAULT, TypeSignature.ofBase("binary")));
        fields.add(new FieldInfo("enumVal", FieldRequirement.DEFAULT, TypeSignature.ofNamed(FooEnum.class)));
        fields.add(new FieldInfo("unionVal", FieldRequirement.DEFAULT, TypeSignature.ofNamed(FooUnion.class)));
        fields.add(new FieldInfo("mapVal", FieldRequirement.DEFAULT,
                                 TypeSignature.ofMap(string, TypeSignature.ofNamed(FooEnum.class))));
        fields.add(new FieldInfo("setVal", FieldRequirement.DEFAULT, TypeSignature.ofSet(FooUnion.class)));
        fields.add(new FieldInfo("listVal", FieldRequirement.DEFAULT, TypeSignature.ofList(string)));
        fields.add(new FieldInfo("selfRef", FieldRequirement.OPTIONAL, TypeSignature.ofNamed(FooStruct.class)));

        final StructInfo fooStruct = newStructInfo(FooStruct.class);
        assertThat(fooStruct).isEqualTo(new StructInfo(FooStruct.class.getName(), fields));
    }

    @Test
    public void incompleteStructMetadata() throws Exception {
        assertThat(toTypeSignature(new FieldValueMetaData(TType.STRUCT)))
                .isEqualTo(TypeSignature.ofUnresolved("unknown"));
    }
}
