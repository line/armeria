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
import static com.linecorp.armeria.internal.server.thrift.ThriftDocStringTestUtil.assumeDocStringsAvailable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.Entry;
import com.linecorp.armeria.internal.server.thrift.ThriftDocServicePlugin.EntryBuilder;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ParamInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.thrift.THttpService;

import testing.thrift.main.FileService;
import testing.thrift.main.FooEnum;
import testing.thrift.main.FooService;
import testing.thrift.main.FooStruct;
import testing.thrift.main.HelloService;
import testing.thrift.main.HelloService.AsyncIface;
import testing.thrift.main.MidLineTagTestService;
import testing.thrift.main.TypeDefService;

class ThriftDocServicePluginTest {

    private static final String HELLO_NAME = HelloService.class.getName();

    private static final String FOO_NAME = FooService.class.getName();

    private static final ThriftDocServicePlugin generator = new ThriftDocServicePlugin();

    private static final ThriftDescriptiveTypeInfoProvider
            descriptiveTypeInfoProvider = new ThriftDescriptiveTypeInfoProvider();

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
        final ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter((plugin, service, method) -> true,
                            (plugin, service, method) -> false), descriptiveTypeInfoProvider);

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
        final ServiceSpecification specification = generator.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter(include, exclude), descriptiveTypeInfoProvider);

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
                                     .fragment("a")
                                     .defaultFormat(ThriftSerializationFormats.BINARY)
                                     .build());
        builder.endpoint(EndpointInfo.builder("*", "/debug/foo")
                                     .fragment("b")
                                     .defaultFormat(ThriftSerializationFormats.TEXT)
                                     .build());
        final Entry entry = builder.build();
        final ServiceInfo service = generator.newServiceInfo(
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
                ParamInfo.of("intVal", TypeSignature.ofBase("i32")),
                ParamInfo.of("foo", foo));
        assertThat(bar3.returnTypeSignature()).isEqualTo(foo);
        assertThat(bar3.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar3.exampleRequests()).isEmpty();

        final MethodInfo bar4 = methods.get("bar4");
        assertThat(bar4.parameters()).containsExactly(
                ParamInfo.of("foos", TypeSignature.ofList(foo)));
        assertThat(bar4.returnTypeSignature()).isEqualTo(TypeSignature.ofList(foo));
        assertThat(bar4.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar4.exampleRequests()).isEmpty();

        final MethodInfo bar5 = methods.get("bar5");
        assertThat(bar5.parameters()).containsExactly(
                ParamInfo.of("foos", TypeSignature.ofMap(string, foo)));
        assertThat(bar5.returnTypeSignature()).isEqualTo(TypeSignature.ofMap(string, foo));
        assertThat(bar5.exceptionTypeSignatures()).hasSize(1);
        assertThat(bar5.exampleRequests()).isEmpty();

        final MethodInfo bar6 = methods.get("bar6");
        assertThat(bar6.parameters()).containsExactly(
                ParamInfo.of("foo1", string),
                ParamInfo.of("foo2", TypeSignature.ofStruct(FooStruct.class)),
                ParamInfo.of("foo3", TypeSignature.ofEnum(FooEnum.class)),
                ParamInfo.of("foo4", TypeSignature.ofMap(string, string)),
                ParamInfo.of("foo5", TypeSignature.ofList(string)),
                ParamInfo.of("foo6", TypeSignature.ofSet(string)),
                ParamInfo.of("foo7", TypeSignature.ofList(TypeSignature.ofList(
                        TypeSignature.ofStruct(FooStruct.class)))),
                ParamInfo.of("foo8", TypeSignature.ofList(TypeSignature.ofList(
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
        final ServiceInfo service = generator.newServiceInfo(
                entry,
                (pluginName, serviceName, methodName) -> true);

        final Map<String, MethodInfo> methods =
                service.methods().stream().collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).hasSize(1);

        final MethodInfo typeDefs = methods.get("typeDefs");
        assertThat(typeDefs.parameters()).containsExactly(
                ParamInfo.of("td1", TypeSignature.ofBase("string")),
                ParamInfo.of("td2", TypeSignature.ofList(TypeSignature.ofBase("string"))),
                ParamInfo.of("td3", TypeSignature.ofBase("bool")),
                ParamInfo.of("td4", TypeSignature.ofList(TypeSignature.ofBase("bool"))),
                ParamInfo.of("td5", TypeSignature.ofBase("i8")),
                ParamInfo.of("td6", TypeSignature.ofList(TypeSignature.ofBase("i8"))),
                ParamInfo.of("td7", TypeSignature.ofBase("i16")),
                ParamInfo.of("td8", TypeSignature.ofList(TypeSignature.ofBase("i16"))),
                ParamInfo.of("td9", TypeSignature.ofBase("i32")),
                ParamInfo.of("td10", TypeSignature.ofList(TypeSignature.ofBase("i32"))),
                ParamInfo.of("td11", TypeSignature.ofBase("i64")),
                ParamInfo.of("td12", TypeSignature.ofList(TypeSignature.ofBase("i64"))),
                ParamInfo.of("td13", TypeSignature.ofBase("double")),
                ParamInfo.of("td14", TypeSignature.ofList(TypeSignature.ofBase("double"))),
                ParamInfo.of("td15", TypeSignature.ofBase("binary")),
                ParamInfo.of("td16", TypeSignature.ofList(TypeSignature.ofBase("binary"))));
    }

    @Test
    void testDocStrings() {
        assumeDocStringsAvailable();

        // Build a server with services that have Javadoc-style comments
        final Server server =
                Server.builder()
                      .service(Route.builder().exact("/hello").build(),
                               THttpService.of(mock(AsyncIface.class)))
                      .service(Route.builder().exact("/file").build(),
                               THttpService.of(mock(FileService.AsyncIface.class)))
                      .service(Route.builder().exact("/midline").build(),
                               THttpService.of(mock(MidLineTagTestService.AsyncIface.class)))
                      .service(Route.builder().exact("/foo").build(),
                               THttpService.of(mock(FooService.AsyncIface.class)))
                      .build();

        final Map<String, DescriptionInfo> allDocStrings = generator.loadDocStrings(
                ImmutableSet.copyOf(server.serviceConfigs()));

        // Filter to only include testing.thrift.main entries
        final Map<String, DescriptionInfo> actualDocStrings = allDocStrings.entrySet().stream()
                .filter(e -> e.getKey().startsWith("testing.thrift.main."))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

        final Map<String, DescriptionInfo> expectedDocStrings = ImmutableMap.<String, DescriptionInfo>builder()
                // HelloService (has Javadoc comments)
                .put("testing.thrift.main.HelloService",
                     DescriptionInfo.of("Tests a non-oneway method with a return value."))
                .put("testing.thrift.main.HelloService/hello",
                     DescriptionInfo.of(
                             "Sends a greeting to the specified name.\n" +
                             "@param string name - the name to greet\n" +
                             "@return a greeting message"))
                .put("testing.thrift.main.HelloService/hello:param/name",
                     DescriptionInfo.of("the name to greet"))
                .put("testing.thrift.main.HelloService/hello:return",
                     DescriptionInfo.of("a greeting message"))

                // FileService (has Javadoc comments)
                .put("testing.thrift.main.FileService",
                     DescriptionInfo.of("Tests exception handling."))
                .put("testing.thrift.main.FileService/create",
                     DescriptionInfo.of(
                             "Creates a file at the specified path.\n" +
                             "@param string path - the path to create\n" +
                             "@throws FileServiceException - when the file cannot be created"))
                .put("testing.thrift.main.FileService/create:param/path",
                     DescriptionInfo.of("the path to create"))
                .put("testing.thrift.main.FileService/create:throws/testing.thrift.main.FileServiceException",
                     DescriptionInfo.of("when the file cannot be created"))

                // FileServiceException
                .put("testing.thrift.main.FileServiceException",
                     DescriptionInfo.of("Exception thrown by FileService."))

                // MidLineTagTestService (service has single-line comment only, methods have Javadoc)
                // Note: Service itself has no Javadoc (only single-line comment)
                .put("testing.thrift.main.MidLineTagTestService/throwsTypeOnly",
                     DescriptionInfo.of(
                             "Method where only the type is specified in the throws tag.\n" +
                             "@param string value - the input value\n" +
                             "@throws FooServiceException"))
                .put("testing.thrift.main.MidLineTagTestService/throwsTypeOnly:param/value",
                     DescriptionInfo.of("the input value"))
                // Note: No description for throwsTypeOnly exception since only type is specified
                .put("testing.thrift.main.MidLineTagTestService/midLineTagsIgnored",
                     DescriptionInfo.of(
                             "Method with mid-line @return should be ignored because tags must be at " +
                             "line start.\n" +
                             "Similarly, mid-line @throws FooServiceException - should also be ignored.\n" +
                             "@param string value - the input value\n" +
                             "@return valid return description"))
                .put("testing.thrift.main.MidLineTagTestService/midLineTagsIgnored:param/value",
                     DescriptionInfo.of("the input value"))
                .put("testing.thrift.main.MidLineTagTestService/midLineTagsIgnored:return",
                     DescriptionInfo.of("valid return description"))

                // Note: FooService has no Javadoc comments on the service or methods
                .build();

        assertThat(actualDocStrings).isEqualTo(expectedDocStrings);
    }
}
