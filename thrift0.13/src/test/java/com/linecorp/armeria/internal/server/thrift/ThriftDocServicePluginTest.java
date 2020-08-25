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

package com.linecorp.armeria.internal.server.thrift;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.newEnumInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.newExceptionInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.newFieldInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.newStructInfo;
import static com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.toTypeSignature;
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
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocServiceFilter;
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
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.FooEnum;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.FooServiceException;
import com.linecorp.armeria.service.test.thrift.main.FooStruct;
import com.linecorp.armeria.service.test.thrift.main.FooUnion;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;

class ThriftDocServicePluginTest {

    private static final String HELLO_NAME = HelloService.class.getName();

    private static final String FOO_NAME = FooService.class.getName();

    private static final ThriftDocServicePlugin generator = new ThriftDocServicePlugin();

    @Test
    void servicesTest() {
        final Map<String, ServiceInfo> services = services((plugin, service, method) -> true,
                                                           (plugin, service, method) -> false);

        assertThat(services).containsOnlyKeys(HELLO_NAME, FOO_NAME);

        // Ensure each service contains the endpoint and does not have example HTTP headers.
        final ServiceInfo helloServiceInfo = services.get(HELLO_NAME);
        assertThat(helloServiceInfo.exampleHeaders()).isEmpty();

        final ServiceInfo fooServiceInfo = services.get(FOO_NAME);
        assertThat(fooServiceInfo.exampleHeaders()).isEmpty();

        // Ensure the example request is empty as well.
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));

        assertThat(methods).containsKey("bar4");
        final MethodInfo bar4 = methods.get("bar4");
        assertThat(bar4.exampleRequests()).isEmpty();
        assertThat(bar4.examplePaths()).containsExactly("/foo");
        assertThat(bar4.endpoints())
                .containsExactly(EndpointInfo.builder("*", "/foo")
                                             .defaultFormat(ThriftSerializationFormats.COMPACT).build());
    }

    @Test
    public void multiplePaths() {
        final Server server =
                Server.builder()
                      .service(Route.builder()
                                    .exact("/foo1")
                                    .build(),
                               THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                                      ThriftSerializationFormats.COMPACT))
                      .service(Route.builder()
                                    .exact("/foo2")
                                    .build(),
                               THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                                      ThriftSerializationFormats.COMPACT))
                      .build();
        final ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter((plugin, service, method) -> true,
                            (plugin, service, method) -> false));

        final ServiceInfo fooServiceInfo = specification.services().iterator().next();
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        final MethodInfo bar4 = methods.get("bar4");
        assertThat(bar4.examplePaths()).containsExactlyInAnyOrder("/foo1", "/foo2");
        assertThat(bar4.endpoints())
                .containsExactlyInAnyOrder(
                        EndpointInfo.builder("*", "/foo1")
                                    .defaultFormat(ThriftSerializationFormats.COMPACT)
                                    .build(),
                        EndpointInfo.builder("*", "/foo2")
                                    .defaultFormat(ThriftSerializationFormats.COMPACT)
                                    .build());
    }

    @Test
    void include() {

        // 1. Nothing specified: include all.
        // 2. Exclude specified: include all except the methods which the exclude filter returns true.
        // 3. Include specified: include the methods which the include filter returns true.
        // 4. Include and exclude specified: include the methods which the include filter returns true and
        //    the exclude filter returns false.

        // 1. Nothing specified.
        DocServiceFilter include = (plugin, service, method) -> true;
        DocServiceFilter exclude = (plugin, service, method) -> false;
        Map<String, ServiceInfo> services = services(include, exclude);
        assertThat(services).containsOnlyKeys(FOO_NAME, HELLO_NAME);

        // 2. Exclude specified.
        exclude = DocServiceFilter.ofMethodName(FOO_NAME, "bar2");
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(FOO_NAME, HELLO_NAME);
        List<String> methods = methods(services);

        assertThat(methods).containsExactlyInAnyOrder("bar1", "bar3", "bar4", "bar5", "bar6");

        // 3-1. Include serviceName specified.
        include = DocServiceFilter.ofServiceName(FOO_NAME);
        // Set the exclude to the default.
        exclude = (plugin, service, method) -> false;
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("bar1", "bar2", "bar3", "bar4", "bar5", "bar6");

        // 3-2. Include methodName specified.
        include = DocServiceFilter.ofMethodName(FOO_NAME, "bar2");
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methods = methods(services);
        assertThat(methods).containsOnlyOnce("bar2");

        // 4-1. Include and exclude specified.
        include = DocServiceFilter.ofServiceName(FOO_NAME);
        exclude = DocServiceFilter.ofMethodName(FOO_NAME, "bar2").or(DocServiceFilter.ofMethodName("bar3"));
        services = services(include, exclude);
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("bar1", "bar4", "bar5", "bar6");

        // 4-2. Include and exclude specified.
        include = DocServiceFilter.ofMethodName(FOO_NAME, "bar2");
        exclude = DocServiceFilter.ofServiceName(FOO_NAME);
        services = services(include, exclude);
        assertThat(services.size()).isZero();
    }

    private static Map<String, ServiceInfo> services(DocServiceFilter include, DocServiceFilter exclude) {
        final Server server =
                Server.builder()
                      .service(Route.builder()
                                    .exact("/hello")
                                    .build(),
                               THttpService.of(mock(AsyncIface.class)))
                      .service(Route.builder()
                                    .exact("/foo")
                                    .build(),
                               THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                                      ThriftSerializationFormats.COMPACT))
                      .build();

        // Generate the specification with the ServiceConfigs.
        final ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter(include, exclude));

        // Ensure the specification contains all services.
        return specification.services()
                            .stream()
                            .collect(toImmutableMap(ServiceInfo::name, Function.identity()));
    }

    private static List<String> methods(Map<String, ServiceInfo> services) {
        return services.get(FOO_NAME).methods()
                       .stream()
                       .map(MethodInfo::name)
                       .collect(toImmutableList());
    }

    @Test
    void testNewEnumInfo() {
        final EnumInfo enumInfo = newEnumInfo(FooEnum.class);

        assertThat(enumInfo).isEqualTo(new EnumInfo(FooEnum.class.getName(),
                                                    Arrays.asList(new EnumValueInfo("VAL1", 1),
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
    void testNewServiceInfo() {
        final ServiceInfo service = generator.newServiceInfo(
                FooService.class,
                ImmutableList.of(EndpointInfo.builder("*", "/foo")
                                             .fragment("a").defaultFormat(ThriftSerializationFormats.BINARY)
                                             .build(),
                                 EndpointInfo.builder("*", "/debug/foo")
                                             .fragment("b").defaultFormat(ThriftSerializationFormats.TEXT)
                                             .build()),
                (pluginName, serviceName, methodName) -> true);

        final Map<String, MethodInfo> methods =
                service.methods().stream().collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).hasSize(6);

        final MethodInfo bar1 = methods.get("bar1");
        assertThat(bar1.parameters()).isEmpty();
        assertThat(bar1.returnTypeSignature()).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(bar1.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar1.exampleRequests()).isEmpty();
        assertThat(bar1.endpoints()).containsExactlyInAnyOrder(
                EndpointInfo.builder("*", "/foo")
                            .fragment("a")
                            .defaultFormat(ThriftSerializationFormats.BINARY)
                            .build(),
                EndpointInfo.builder("*", "/debug/foo")
                            .fragment("b")
                            .defaultFormat(ThriftSerializationFormats.TEXT)
                            .build());

        final TypeSignature string = TypeSignature.ofBase("string");
        final MethodInfo bar2 = methods.get("bar2");
        assertThat(bar2.parameters()).isEmpty();
        assertThat(bar2.returnTypeSignature()).isEqualTo(string);
        assertThat(bar2.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar2.exampleRequests()).isEmpty();

        final TypeSignature foo = TypeSignature.ofNamed(FooStruct.class);
        final MethodInfo bar3 = methods.get("bar3");
        assertThat(bar3.parameters()).containsExactly(
                FieldInfo.of("intVal", TypeSignature.ofBase("i32")),
                FieldInfo.of("foo", foo));
        assertThat(bar3.returnTypeSignature()).isEqualTo(foo);
        assertThat(bar3.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar3.exampleRequests()).isEmpty();

        final MethodInfo bar4 = methods.get("bar4");
        assertThat(bar4.parameters()).containsExactly(
                FieldInfo.of("foos", TypeSignature.ofList(foo)));
        assertThat(bar4.returnTypeSignature()).isEqualTo(TypeSignature.ofList(foo));
        assertThat(bar4.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar4.exampleRequests()).isEmpty();

        final MethodInfo bar5 = methods.get("bar5");
        assertThat(bar5.parameters()).containsExactly(
                FieldInfo.of("foos", TypeSignature.ofMap(string, foo)));
        assertThat(bar5.returnTypeSignature()).isEqualTo(TypeSignature.ofMap(string, foo));
        assertThat(bar5.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar5.exampleRequests()).isEmpty();

        final MethodInfo bar6 = methods.get("bar6");
        assertThat(bar6.parameters()).containsExactly(
                FieldInfo.of("foo1", string),
                FieldInfo.of("foo2", TypeSignature.ofUnresolved("TypedefedStruct")),
                FieldInfo.of("foo3", TypeSignature.ofUnresolved("TypedefedEnum")),
                FieldInfo.of("foo4", TypeSignature.ofUnresolved("TypedefedMap")),
                FieldInfo.of("foo5", TypeSignature.ofUnresolved("TypedefedList")),
                FieldInfo.of("foo6", TypeSignature.ofUnresolved("TypedefedSet")),
                FieldInfo.of("foo7", TypeSignature.ofUnresolved("NestedTypedefedStructs")),
                FieldInfo.of("foo8", TypeSignature.ofList(TypeSignature.ofList(
                        TypeSignature.ofUnresolved("TypedefedStruct")))));

        assertThat(bar6.returnTypeSignature()).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(bar6.exceptionTypeSignatures()).isEmpty();
        assertThat(bar6.exampleRequests()).isEmpty();

        final List<HttpHeaders> exampleHeaders = service.exampleHeaders();
        assertThat(exampleHeaders).isEmpty();
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
        fields.add(FieldInfo.of("enumVal", TypeSignature.ofNamed(FooEnum.class)));
        fields.add(FieldInfo.of("unionVal", TypeSignature.ofNamed(FooUnion.class)));
        fields.add(FieldInfo.of("mapVal", TypeSignature.ofMap(
                string, TypeSignature.ofNamed(FooEnum.class))));
        fields.add(FieldInfo.of("setVal", TypeSignature.ofSet(FooUnion.class)));
        fields.add(FieldInfo.of("listVal", TypeSignature.ofList(string)));
        fields.add(FieldInfo.builder("selfRef", TypeSignature.ofNamed(FooStruct.class))
                            .requirement(FieldRequirement.OPTIONAL).build());

        final StructInfo fooStruct = newStructInfo(FooStruct.class);
        assertThat(fooStruct).isEqualTo(new StructInfo(FooStruct.class.getName(), fields));
    }

    @Test
    void incompleteStructMetadata() throws Exception {
        assertThat(toTypeSignature(new FieldValueMetaData(TType.STRUCT)))
                .isEqualTo(TypeSignature.ofUnresolved("unknown"));
    }
}
