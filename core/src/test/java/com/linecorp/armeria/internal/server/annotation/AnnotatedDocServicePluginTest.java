/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.LONG;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.STRING;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.VOID;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.endpointInfo;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.newDescriptiveTypeInfo;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.internal.server.annotation.DefaultDescriptiveTypeInfoProviderTest.REQUEST_STRUCT_INFO_PROVIDER;
import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static com.linecorp.armeria.server.docs.FieldLocation.HEADER;
import static com.linecorp.armeria.server.docs.FieldLocation.QUERY;
import static com.linecorp.armeria.server.docs.FieldRequirement.REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RouteBuilder;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeSignature;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.docs.TypeSignatureType;

class AnnotatedDocServicePluginTest {

    private static final String FOO_NAME = FooClass.class.getName();

    private static final String BAR_NAME = BarClass.class.getName();

    private static final AnnotatedDocServicePlugin plugin = new AnnotatedDocServicePlugin();

    @Test
    void testToTypeSignature() throws Exception {
        assertThat(toTypeSignature(Void.class)).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(toTypeSignature(void.class)).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(toTypeSignature(Boolean.class)).isEqualTo(TypeSignature.ofBase("boolean"));
        assertThat(toTypeSignature(boolean.class)).isEqualTo(TypeSignature.ofBase("boolean"));
        assertThat(toTypeSignature(Byte.class)).isEqualTo(TypeSignature.ofBase("byte"));
        assertThat(toTypeSignature(byte.class)).isEqualTo(TypeSignature.ofBase("byte"));
        assertThat(toTypeSignature(Short.class)).isEqualTo(TypeSignature.ofBase("short"));
        assertThat(toTypeSignature(short.class)).isEqualTo(TypeSignature.ofBase("short"));
        assertThat(toTypeSignature(Integer.class)).isEqualTo(TypeSignature.ofBase("int"));
        assertThat(toTypeSignature(int.class)).isEqualTo(TypeSignature.ofBase("int"));
        assertThat(toTypeSignature(Long.class)).isEqualTo(TypeSignature.ofBase("long"));
        assertThat(toTypeSignature(long.class)).isEqualTo(TypeSignature.ofBase("long"));
        assertThat(toTypeSignature(Float.class)).isEqualTo(TypeSignature.ofBase("float"));
        assertThat(toTypeSignature(float.class)).isEqualTo(TypeSignature.ofBase("float"));
        assertThat(toTypeSignature(Double.class)).isEqualTo(TypeSignature.ofBase("double"));
        assertThat(toTypeSignature(double.class)).isEqualTo(TypeSignature.ofBase("double"));
        assertThat(toTypeSignature(String.class)).isEqualTo(TypeSignature.ofBase("string"));

        assertThat(toTypeSignature(Byte[].class)).isEqualTo(TypeSignature.ofBase("binary"));
        assertThat(toTypeSignature(byte[].class)).isEqualTo(TypeSignature.ofBase("binary"));

        assertThat(toTypeSignature(int[].class)).isEqualTo(TypeSignature.ofList(TypeSignature.ofBase("int")));

        final TypeSignature typeVariable = toTypeSignature(FieldContainer.class.getDeclaredField("typeVariable")
                                                                               .getGenericType());
        assertThat(typeVariable).isEqualTo(TypeSignature.ofBase("T"));

        // Container types.

        final TypeSignature list = toTypeSignature(FieldContainer.class.getDeclaredField("list")
                                                                       .getGenericType());
        assertThat(list).isEqualTo(TypeSignature.ofList(TypeSignature.ofBase("string")));

        final TypeSignature set = toTypeSignature(FieldContainer.class.getDeclaredField("set")
                                                                      .getGenericType());
        assertThat(set).isEqualTo(TypeSignature.ofSet(TypeSignature.ofBase("float")));

        final TypeSignature map = toTypeSignature(FieldContainer.class.getDeclaredField("map")
                                                                      .getGenericType());
        assertThat(map).isEqualTo(TypeSignature.ofMap(TypeSignature.ofBase("long"),
                                                      TypeSignature.ofUnresolved("")));

        final TypeSignature future = toTypeSignature(FieldContainer.class.getDeclaredField("future")
                                                                         .getGenericType());
        assertThat(future).isEqualTo(TypeSignature.ofContainer("CompletableFuture",
                                                               TypeSignature.ofBase("double")));

        final TypeSignature typeVariableFuture =
                toTypeSignature(FieldContainer.class.getDeclaredField("typeVariableFuture").getGenericType());
        assertThat(typeVariableFuture).isEqualTo(TypeSignature.ofContainer("CompletableFuture",
                                                                           TypeSignature.ofBase("T")));

        final TypeSignature genericArray =
                toTypeSignature(FieldContainer.class.getDeclaredField("genericArray").getGenericType());
        assertThat(genericArray).isEqualTo(
                TypeSignature.ofList(TypeSignature.ofList(TypeSignature.ofBase("string"))));

        final TypeSignature biFunction =
                toTypeSignature(FieldContainer.class.getDeclaredField("biFunction").getGenericType());
        assertThat(biFunction).isEqualTo(TypeSignature.ofContainer(
                "BiFunction",
                TypeSignature.ofStruct(JsonNode.class),
                TypeSignature.ofUnresolved(""),
                TypeSignature.ofBase("string")));

        assertThat(toTypeSignature(FieldContainer.class)).isEqualTo(
                TypeSignature.ofStruct(FieldContainer.class));
        final TypeSignature optional =
                toTypeSignature(FieldContainer.class.getDeclaredField("optional").getGenericType());
        assertThat(optional).isEqualTo(TypeSignature.ofOptional(TypeSignature.ofBase("string")));
    }

    @Test
    void testNewEndpointInfo() {
        final String hostnamePattern = "*";

        Route route = withMethodAndTypes(Route.builder().path("/path"));
        EndpointInfo endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(EndpointInfo.builder("*", "exact:/path")
                                                       .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                           MediaType.JSON_UTF_8)
                                                       .build());

        route = withMethodAndTypes(Route.builder().path("prefix:/bar/baz"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(EndpointInfo.builder("*", "prefix:/bar/baz/")
                                                       .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                           MediaType.JSON_UTF_8)
                                                       .build());

        route = withMethodAndTypes(Route.builder().path("glob:/home/*/files/**"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(EndpointInfo.builder("*", "regex:^/home/([^/]+)/files/(.*)$")
                                                       .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                           MediaType.JSON_UTF_8)
                                                       .build());

        route = withMethodAndTypes(Route.builder().path("glob:/foo"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(EndpointInfo.builder("*", "exact:/foo")
                                                       .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                           MediaType.JSON_UTF_8)
                                                       .build());

        route = withMethodAndTypes(Route.builder().path("regex:^/files/(?<filePath>.*)$"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(EndpointInfo.builder("*", "regex:^/files/(?<filePath>.*)$")
                                                       .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                           MediaType.JSON_UTF_8)
                                                       .build());

        route = withMethodAndTypes(Route.builder().path("/service/{value}/test/:value2/something"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                EndpointInfo.builder("*", "/service/:value/test/:value2/something")
                            .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                            .build());

        route = withMethodAndTypes(Route.builder().path("/service/{value}/test/{*value2}"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                EndpointInfo.builder("*", "/service/:value/test/:*value2")
                            .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                            .build());

        route = withMethodAndTypes(Route.builder().path("/glob/", "glob:/home/*/files/**"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                EndpointInfo.builder("*", "regex:^/glob/home/([^/]+)/files/(.*)$")
                            .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                            .build());

        route = withMethodAndTypes(Route.builder()
                                        .path("/prefix: regex:/", "regex:^/files/(?<filePath>.*)$"));
        endpointInfo = endpointInfo(route, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                EndpointInfo.builder("*", "regex:^/files/(?<filePath>.*)$")
                            .regexPathPrefix("prefix:/prefix: regex:/")
                            .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                            .build());
    }

    private static Route withMethodAndTypes(RouteBuilder builder) {
        return builder.methods(HttpMethod.GET)
                      .consumes(MediaType.PLAIN_TEXT_UTF_8)
                      .produces(MediaType.JSON_UTF_8)
                      .build();
    }

    @Test
    void testGenerateSpecification() {
        final Map<String, ServiceInfo> services = services((plugin, service, method) -> true,
                                                           (plugin, service, method) -> false,
                                                           new FooClass(),
                                                           new BarClass());

        assertThat(services).containsOnlyKeys(FOO_NAME, BAR_NAME);
        checkFooService(services.get(FOO_NAME));
        checkBarService(services.get(BAR_NAME));
    }

    @Test
    void include() {

        // 1. Nothing specified: include all methods.
        // 2. Exclude specified: include all methods except the methods which the exclude filter returns true.
        // 3. Include specified: include the methods which the include filter returns true.
        // 4. Include and exclude specified: include the methods which the include filter returns true and
        //    the exclude filter returns false.

        // 1. Nothing specified.
        DocServiceFilter include = (plugin, service, method) -> true;
        DocServiceFilter exclude = (plugin, service, method) -> false;
        Map<String, ServiceInfo> services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services).containsOnlyKeys(FOO_NAME, BAR_NAME);

        // 2. Exclude specified.
        exclude = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services).containsOnlyKeys(FOO_NAME, BAR_NAME);

        List<String> methods = methods(services);
        assertThat(methods).containsOnlyOnce("foo2Method");

        // 3-1. Include serviceName specified.
        include = DocServiceFilter.ofServiceName(FOO_NAME);
        // Set the exclude to the default.
        exclude = (plugin, service, method) -> false;
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methods = methods(services);
        assertThat(methods).containsExactlyInAnyOrder("fooMethod", "foo2Method");

        // 3-2. Include methodName specified.
        include = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methods = methods(services);
        assertThat(methods).containsOnlyOnce("fooMethod");

        // 4-1. Include and exclude specified.
        include = DocServiceFilter.ofServiceName(FOO_NAME);
        exclude = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        services = services(include, exclude, new FooClass());
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methods = methods(services);
        assertThat(methods).containsOnlyOnce("foo2Method");

        // 4-2. Include and exclude specified.
        include = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        exclude = DocServiceFilter.ofServiceName(FOO_NAME);
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services.size()).isZero();
    }

    @Test
    void testMultiPath() {
        final Map<String, ServiceInfo> services = services(new MultiPathClass());
        final Map<String, MethodInfo> methods =
                services.get(MultiPathClass.class.getName()).methods().stream()
                        .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        final Set<String> paths = methods.get("multiGet").endpoints()
                                         .stream().map(EndpointInfo::pathMapping)
                                         .collect(toImmutableSet());
        assertThat(paths).containsOnly("exact:/path1", "exact:/path2");
    }

    private static void checkFooService(ServiceInfo fooServiceInfo) {
        assertThat(fooServiceInfo.exampleHeaders()).isEmpty();
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).containsKeys("fooMethod", "foo2Method");

        final MethodInfo fooMethod = methods.get("fooMethod");
        assertThat(fooMethod.exampleHeaders()).isEmpty();
        assertThat(fooMethod.exampleRequests()).isEmpty();

        assertThat(fooMethod.parameters()).hasSize(2);
        assertThat(fooMethod.parameters()).containsExactlyInAnyOrder(
                FieldInfo.builder("foo", STRING).requirement(REQUIRED)
                         .location(QUERY)
                         .build(),
                FieldInfo.builder("foo1", LONG).requirement(REQUIRED)
                         .location(HEADER)
                         .build());

        assertThat(fooMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(fooMethod.endpoints()).containsExactly(EndpointInfo.builder("*", "exact:/foo")
                                                                      .defaultMimeType(MediaType.JSON)
                                                                      .build());
    }

    private static void checkBarService(ServiceInfo barServiceInfo) {
        assertThat(barServiceInfo.exampleHeaders()).isEmpty();
        final Map<String, MethodInfo> methods =
                barServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).containsKeys("barMethod");

        final MethodInfo barMethod = methods.get("barMethod");
        assertThat(barMethod.exampleHeaders()).isEmpty();
        assertThat(barMethod.exampleRequests()).isEmpty();
        assertThat(barMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(barMethod.endpoints()).containsExactly(EndpointInfo.builder("*", "exact:/bar")
                                                                      .defaultMimeType(MediaType.JSON)
                                                                      .build());

        final FieldInfo bar = FieldInfo.builder("bar", STRING).requirement(REQUIRED)
                                       .location(QUERY)
                                       .build();
        final List<FieldInfo> fieldInfos = barMethod.parameters();
        assertThat(fieldInfos).hasSize(2);
        assertThat(fieldInfos).contains(bar);
        final Optional<FieldInfo> compositeBean =
                fieldInfos.stream()
                          .filter(fieldInfo -> "compositeBean".equals(fieldInfo.name()))
                          .findFirst();
        assertThat(compositeBean).isPresent();
        final StructInfo expected = new StructInfo(
                CompositeBean.class.getName(),
                ImmutableList.of(createBean("bean1", RequestBean1.class),
                                 createBean("bean2", RequestBean2.class)));
        final DescriptiveTypeInfo actual = newDescriptiveTypeInfo(
                (DescriptiveTypeSignature) compositeBean.get().typeSignature(), REQUEST_STRUCT_INFO_PROVIDER,
                ImmutableSet.of());
        assertThat(actual).isEqualTo(expected);
    }

    private static Map<String, ServiceInfo> services(Object... services) {
        final DocServiceFilter include = (plugin, service, method) -> true;
        final DocServiceFilter exclude = (plugin, service, method) -> false;
        return services(include, exclude, services);
    }

    private static Map<String, ServiceInfo> services(DocServiceFilter include,
                                                     DocServiceFilter exclude,
                                                     Object... services) {
        final ServerBuilder builder = Server.builder();
        Arrays.stream(services).forEach(builder::annotatedService);
        final Server server = builder.build();
        final ServiceSpecification specification =
                plugin.generateSpecification(ImmutableSet.copyOf(server.serviceConfigs()),
                                             unifyFilter(include, exclude), typeDescriptor -> null);
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

    static FieldInfo compositeBean() {
        return FieldInfo.builder("compositeBean",
                                 new RequestObjectTypeSignature(TypeSignatureType.STRUCT,
                                                                CompositeBean.class.getName(),
                                                                CompositeBean.class, ImmutableList.of()))
                        .requirement(REQUIRED)
                        .build();
    }

    private static FieldInfo createBean(String name, Class<?> type) {
        return FieldInfo.builder(name, TypeSignature.ofStruct(type))
                        .requirement(REQUIRED)
                        .build();
    }

    private static class FieldContainer<T> {
        T typeVariable;
        List<String> list;
        Set<Float> set;
        Map<Long, ?> map;
        CompletableFuture<Double> future;
        CompletableFuture<T> typeVariableFuture;
        List<String>[] genericArray;
        BiFunction<JsonNode, ?, String> biFunction;
        Optional<String> optional;
    }

    private static class FooClass {

        @Get("/foo")
        public void fooMethod(@Param String foo, @Header long foo1) {}

        @Get("/foo2")
        public long foo2Method(@Param String foo2) {
            return 0;
        }
    }

    private static class BarClass {
        @Get("/bar")
        public void barMethod(@Param String bar, CompositeBean compositeBean) {}
    }

    private static class MultiPathClass {
        @Get
        @Path("/path1")
        @Path("/path2")
        public void multiGet() {}
    }

    static class CompositeBean {
        @RequestObject
        private RequestBean1 bean1;

        @RequestObject
        private RequestBean2 bean2;
    }

    static class RequestBean1 {
        @Nullable
        @JsonProperty
        @Param
        private Long seqNum;

        @JsonProperty
        private String uid;

        @Nullable
        private String notPopulatedStr;

        RequestBean1(@Header String uid) {
            this.uid = uid;
        }
    }

    static class RequestBean2 {

        @JsonProperty
        private InsideBean insideBean;

        public void setInsideBean(@RequestObject InsideBean insideBean) {
            this.insideBean = insideBean;
        }

        static class InsideBean {

            @JsonProperty
            @Param
            private Long inside1;

            @JsonProperty
            @Param
            private int inside2;
        }
    }
}
