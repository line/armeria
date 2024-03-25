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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.Entry;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.EntryBuilder;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.thrift.THttpService;

import testing.thrift.main.FooEnum;
import testing.thrift.main.FooService;
import testing.thrift.main.FooStruct;
import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.AsyncIface;
import testing.thrift.main.TypeDefService;

/**
 * The generated code of `FooService` is different from thrift0.13 compiler.
 * The foo2 in {@code public void bar6(java.lang.String foo1, FooStruct foo2 ...} is created using
 * {@code org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRUCT, "...")));}
 * in thrift0.13. On the other hand, it's created using
 * {@code org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT,
 * FooStruct.class)));} in thrift0.17.
 */
class ThriftDocServicePluginTest {

    private static final String HELLO_NAME = HelloService.class.getName();

    private static final String FOO_NAME = FooService.class.getName();

    private static final ThriftDocServicePlugin GENERATOR = new ThriftDocServicePlugin();

    private static final ThriftDescriptiveTypeInfoProvider DESCRIPTIVE_TYPE_INFO_PROVIDER =
            new ThriftDescriptiveTypeInfoProvider();

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
        final ServiceSpecification specification = GENERATOR.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter((plugin, service, method) -> true,
                            (plugin, service, method) -> false), DESCRIPTIVE_TYPE_INFO_PROVIDER);

        final ServiceInfo fooServiceInfo = specification.services().iterator().next();
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        final MethodInfo bar4 = methods.get("bar4");
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
        final ServiceSpecification specification = GENERATOR.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter(include, exclude), DESCRIPTIVE_TYPE_INFO_PROVIDER);

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
    void testNewServiceInfo() {
        final EntryBuilder builder = new EntryBuilder(FooService.class);
        builder.endpoint(EndpointInfo.builder("*", "/foo")
                                     .fragment("a").defaultFormat(ThriftSerializationFormats.BINARY)
                                     .build());
        builder.endpoint(EndpointInfo.builder("*", "/debug/foo")
                                     .fragment("b").defaultFormat(ThriftSerializationFormats.TEXT)
                                     .build());
        final Entry entry = builder.build();
        final ServiceInfo service = GENERATOR.newServiceInfo(
                entry,
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

        final TypeSignature foo = TypeSignature.ofStruct(FooStruct.class);
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
                FieldInfo.of("foo2", TypeSignature.ofStruct(FooStruct.class)),
                FieldInfo.of("foo3", TypeSignature.ofEnum(FooEnum.class)),
                FieldInfo.of("foo4", TypeSignature.ofMap(string, string)),
                FieldInfo.of("foo5", TypeSignature.ofList(string)),
                FieldInfo.of("foo6", TypeSignature.ofSet(string)),
                FieldInfo.of("foo7", TypeSignature.ofList(TypeSignature.ofList(
                        TypeSignature.ofStruct(FooStruct.class)))),
                FieldInfo.of("foo8", TypeSignature.ofList(TypeSignature.ofList(
                        TypeSignature.ofStruct(FooStruct.class)))));

        assertThat(bar6.returnTypeSignature()).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(bar6.exceptionTypeSignatures()).isEmpty();
        assertThat(bar6.exampleRequests()).isEmpty();

        final List<HttpHeaders> exampleHeaders = service.exampleHeaders();
        assertThat(exampleHeaders).isEmpty();
    }

    @Test
    void typeDefService() {
        final EntryBuilder builder = new EntryBuilder(TypeDefService.class);
        builder.endpoint(EndpointInfo.builder("*", "/typeDef")
                                     .defaultFormat(ThriftSerializationFormats.BINARY)
                                     .build());
        final Entry entry = builder.build();
        final ServiceInfo service = GENERATOR.newServiceInfo(
                entry,
                (pluginName, serviceName, methodName) -> true);

        final Map<String, MethodInfo> methods =
                service.methods().stream().collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).hasSize(1);

        final MethodInfo typeDefs = methods.get("typeDefs");
        assertThat(typeDefs.parameters()).containsExactly(
                FieldInfo.of("td1", TypeSignature.ofBase("string")),
                FieldInfo.of("td2", TypeSignature.ofList(TypeSignature.ofBase("string"))),
                FieldInfo.of("td3", TypeSignature.ofBase("bool")),
                FieldInfo.of("td4", TypeSignature.ofList(TypeSignature.ofBase("bool"))),
                FieldInfo.of("td5", TypeSignature.ofBase("i8")),
                FieldInfo.of("td6", TypeSignature.ofList(TypeSignature.ofBase("i8"))),
                FieldInfo.of("td7", TypeSignature.ofBase("i16")),
                FieldInfo.of("td8", TypeSignature.ofList(TypeSignature.ofBase("i16"))),
                FieldInfo.of("td9", TypeSignature.ofBase("i32")),
                FieldInfo.of("td10", TypeSignature.ofList(TypeSignature.ofBase("i32"))),
                FieldInfo.of("td11", TypeSignature.ofBase("i64")),
                FieldInfo.of("td12", TypeSignature.ofList(TypeSignature.ofBase("i64"))),
                FieldInfo.of("td13", TypeSignature.ofBase("double")),
                FieldInfo.of("td14", TypeSignature.ofList(TypeSignature.ofBase("double"))),
                FieldInfo.of("td15", TypeSignature.ofBase("binary")),
                FieldInfo.of("td16", TypeSignature.ofList(TypeSignature.ofBase("binary"))));
    }
}
