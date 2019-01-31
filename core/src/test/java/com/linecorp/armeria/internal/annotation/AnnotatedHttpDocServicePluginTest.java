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

package com.linecorp.armeria.internal.annotation;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.BEAN;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.INT;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.LONG;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.STRING;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.VOID;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.endpointInfo;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.server.docs.FieldLocation.HEADER;
import static com.linecorp.armeria.server.docs.FieldLocation.QUERY;
import static com.linecorp.armeria.server.docs.FieldRequirement.REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePluginTest.RequestBean2.InsideBean;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory.PrefixAddingPathMapping;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldInfoBuilder;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;

public class AnnotatedHttpDocServicePluginTest {

    private final AnnotatedHttpDocServicePlugin plugin = new AnnotatedHttpDocServicePlugin();

    @Test
    public void testToTypeSignature() throws Exception {
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
        assertThat(biFunction).isEqualTo(TypeSignature.ofContainer("BiFunction",
                                                                   TypeSignature.ofBase("JsonNode"),
                                                                   TypeSignature.ofUnresolved(""),
                                                                   TypeSignature.ofBase("string")));

        assertThat(toTypeSignature(FieldContainer.class)).isEqualTo(TypeSignature.ofBase("FieldContainer"));
    }

    @Test
    public void testNewEndpointInfo() {
        final String hostnamePattern = "*";

        PathMapping mapping = newHttpHeaderPathMapping(PathMapping.of("/path"));
        EndpointInfo endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(new EndpointInfoBuilder("*", "exact:/path")
                                                   .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                       MediaType.JSON_UTF_8)
                                                   .build());

        mapping = newHttpHeaderPathMapping(PathMapping.of("prefix:/bar/baz"));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(new EndpointInfoBuilder("*", "prefix:/bar/baz/")
                                                   .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                       MediaType.JSON_UTF_8)
                                                   .build());

        mapping = newHttpHeaderPathMapping(PathMapping.of("glob:/home/*/files/**"));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(new EndpointInfoBuilder("*", "regex:^/home/([^/]+)/files/(.*)$")
                                                   .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                       MediaType.JSON_UTF_8)
                                                   .build());

        mapping = newHttpHeaderPathMapping(PathMapping.of("glob:/foo"));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(new EndpointInfoBuilder("*", "exact:/foo")
                                                   .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                       MediaType.JSON_UTF_8)
                                                   .build());

        mapping = newHttpHeaderPathMapping(PathMapping.of("regex:^/files/(?<filePath>.*)$"));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(new EndpointInfoBuilder("*", "regex:^/files/(?<filePath>.*)$")
                                                   .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8,
                                                                       MediaType.JSON_UTF_8)
                                                   .build());

        mapping = newHttpHeaderPathMapping(PathMapping.of("/service/{value}/test/:value2/something"));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                new EndpointInfoBuilder("*", "/service/{value}/test/{value2}/something")
                        .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                        .build());

        // PrefixAddingPathMapping

        mapping = newHttpHeaderPathMapping(
                new PrefixAddingPathMapping("/glob/", PathMapping.of("glob:/home/*/files/**")));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                new EndpointInfoBuilder("*", "regex:^/home/([^/]+)/files/(.*)$")
                        .regexPathPrefix("prefix:/glob/")
                        .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                        .build());

        mapping = newHttpHeaderPathMapping(
                new PrefixAddingPathMapping("/prefix: regex:/",
                                            PathMapping.of("regex:^/files/(?<filePath>.*)$")));
        endpointInfo = endpointInfo(mapping, hostnamePattern);
        assertThat(endpointInfo).isEqualTo(
                new EndpointInfoBuilder("*", "regex:^/files/(?<filePath>.*)$")
                        .regexPathPrefix("prefix:/prefix: regex:/")
                        .availableMimeTypes(MediaType.PLAIN_TEXT_UTF_8, MediaType.JSON_UTF_8)
                        .build());
    }

    private static PathMapping newHttpHeaderPathMapping(PathMapping pathMapping) {
        return pathMapping.withHttpHeaderInfo(ImmutableSet.of(HttpMethod.GET),
                                              ImmutableList.of(MediaType.PLAIN_TEXT_UTF_8),
                                              ImmutableList.of(MediaType.JSON_UTF_8));
    }

    @Test
    public void testGenerateSpecification() {
        final ServiceSpecification specification = plugin.generateSpecification(
                ImmutableSet.copyOf(serviceConfigs()));

        // Ensure the specification contains all services.
        final Map<String, ServiceInfo> services =
                specification.services().stream()
                             .collect(toImmutableMap(ServiceInfo::name, Function.identity()));

        assertThat(services).containsOnlyKeys(FooClass.class.getName(), BarClass.class.getName());

        checkFooService(services.get(FooClass.class.getName()));
        checkBarService(services.get(BarClass.class.getName()));
    }

    private static void checkFooService(ServiceInfo fooServiceInfo) {
        assertThat(fooServiceInfo.exampleHttpHeaders()).isEmpty();
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).containsKeys("fooMethod", "foo2Method");

        final MethodInfo fooMethod = methods.get("fooMethod");
        assertThat(fooMethod.exampleHttpHeaders()).isEmpty();
        assertThat(fooMethod.exampleRequests()).isEmpty();

        assertThat(fooMethod.parameters()).hasSize(2);
        assertThat(fooMethod.parameters()).containsExactlyInAnyOrder(
                new FieldInfoBuilder("foo", STRING).requirement(REQUIRED)
                                                   .location(QUERY)
                                                   .build(),
                new FieldInfoBuilder("foo1", LONG).requirement(REQUIRED)
                                                  .location(HEADER)
                                                  .build());

        assertThat(fooMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(fooMethod.endpoints()).containsExactly(
                new EndpointInfoBuilder("*", "exact:/foo").defaultMimeType(MediaType.JSON).build());
    }

    private static void checkBarService(ServiceInfo barServiceInfo) {
        assertThat(barServiceInfo.exampleHttpHeaders()).isEmpty();
        final Map<String, MethodInfo> methods =
                barServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).containsKeys("barMethod");

        final MethodInfo barMethod = methods.get("barMethod");
        assertThat(barMethod.exampleHttpHeaders()).isEmpty();
        assertThat(barMethod.exampleRequests()).isEmpty();
        assertThat(barMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(barMethod.endpoints()).containsExactly(
                new EndpointInfoBuilder("*", "exact:/bar").defaultMimeType(MediaType.JSON).build());

        final FieldInfo bar = new FieldInfoBuilder("bar", STRING).requirement(REQUIRED)
                                                                 .location(QUERY)
                                                                 .build();
        final List<FieldInfo> fieldInfos = barMethod.parameters();
        assertFieldInfos(fieldInfos, ImmutableList.of(bar, compositeBean()));
    }

    private static List<ServiceConfig> serviceConfigs() {
        final List<AnnotatedHttpServiceElement> fooElements = AnnotatedHttpServiceFactory.find(
                "/", new FooClass(), ImmutableList.of());
        final List<AnnotatedHttpServiceElement> barElements = AnnotatedHttpServiceFactory.find(
                "/", new BarClass(), ImmutableList.of());
        final ImmutableList.Builder<ServiceConfig> builder = ImmutableList.builder();
        final VirtualHost virtualHost = new VirtualHostBuilder("*").build();
        fooElements.forEach(element -> builder.add(
                new ServiceConfig(virtualHost, element.pathMapping(), element.service(), null)));
        barElements.forEach(element -> builder.add(
                new ServiceConfig(virtualHost, element.pathMapping(), element.service(), null)));
        return builder.build();
    }

    static FieldInfo compositeBean() {
        return new FieldInfoBuilder(CompositeBean.class.getSimpleName(), BEAN,
                                    createBean1(), createBean2()).build();
    }

    private static FieldInfo createBean1() {
        final FieldInfo uid = new FieldInfoBuilder("uid", STRING).location(HEADER).requirement(REQUIRED)
                                                                 .build();
        final FieldInfo seqNum = new FieldInfoBuilder("seqNum", LONG).location(QUERY).requirement(REQUIRED)
                                                                     .build();
        return new FieldInfoBuilder(RequestBean1.class.getSimpleName(), BEAN, uid, seqNum).build();
    }

    private static FieldInfo createBean2() {
        final FieldInfo inside1 = new FieldInfoBuilder("inside1", LONG).location(QUERY).requirement(REQUIRED)
                                                                       .build();
        final FieldInfo inside2 = new FieldInfoBuilder("inside2", INT).location(QUERY).requirement(REQUIRED)
                                                                      .build();
        final FieldInfo insideBean = new FieldInfoBuilder(InsideBean.class.getSimpleName(), BEAN,
                                                          inside1, inside2).build();
        return new FieldInfoBuilder(RequestBean2.class.getSimpleName(), BEAN, insideBean).build();
    }

    private static void assertFieldInfos(List<FieldInfo> fieldInfos, List<FieldInfo> expected) {
        final Comparator<List<FieldInfo>> comparator = (o1, o2) -> {
            assertFieldInfos(o1, o2);
            // If assertFieldInfos does not throw an exception, it contains same elements.
            return 0;
        };
        assertThat(fieldInfos).usingComparatorForElementFieldsWithNames(
                comparator,
                "childFieldInfos").usingFieldByFieldElementComparator().containsExactlyInAnyOrderElementsOf(
                expected);
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
