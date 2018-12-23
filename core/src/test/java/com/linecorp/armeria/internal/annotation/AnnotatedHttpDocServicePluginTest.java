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
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.INT64;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.STRING;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.VOID;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.endpointInfo;
import static com.linecorp.armeria.internal.annotation.AnnotatedHttpDocServicePlugin.toTypeSignature;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory.PrefixAddingPathMapping;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
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
        assertThat(toTypeSignature(Byte.class)).isEqualTo(TypeSignature.ofBase("int8"));
        assertThat(toTypeSignature(byte.class)).isEqualTo(TypeSignature.ofBase("int8"));
        assertThat(toTypeSignature(Short.class)).isEqualTo(TypeSignature.ofBase("int16"));
        assertThat(toTypeSignature(short.class)).isEqualTo(TypeSignature.ofBase("int16"));
        assertThat(toTypeSignature(Integer.class)).isEqualTo(TypeSignature.ofBase("int32"));
        assertThat(toTypeSignature(int.class)).isEqualTo(TypeSignature.ofBase("int32"));
        assertThat(toTypeSignature(Long.class)).isEqualTo(TypeSignature.ofBase("int64"));
        assertThat(toTypeSignature(long.class)).isEqualTo(TypeSignature.ofBase("int64"));
        assertThat(toTypeSignature(Float.class)).isEqualTo(TypeSignature.ofBase("float"));
        assertThat(toTypeSignature(float.class)).isEqualTo(TypeSignature.ofBase("float"));
        assertThat(toTypeSignature(Double.class)).isEqualTo(TypeSignature.ofBase("double"));
        assertThat(toTypeSignature(double.class)).isEqualTo(TypeSignature.ofBase("double"));
        assertThat(toTypeSignature(String.class)).isEqualTo(TypeSignature.ofBase("string"));

        assertThat(toTypeSignature(Byte[].class)).isEqualTo(TypeSignature.ofBase("binary"));
        assertThat(toTypeSignature(byte[].class)).isEqualTo(TypeSignature.ofBase("binary"));

        assertThat(toTypeSignature(int[].class)).isEqualTo(TypeSignature.ofList(TypeSignature.ofBase("int32")));

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
        assertThat(map).isEqualTo(TypeSignature.ofMap(TypeSignature.ofBase("int64"),
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

        final ServiceInfo fooServiceInfo = services.get(FooClass.class.getName());
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
                new FieldInfo("foo", FieldRequirement.REQUIRED, STRING),
                new FieldInfo("foo1", FieldRequirement.REQUIRED, INT64));

        assertThat(fooMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(fooMethod.endpoints()).containsExactly(
                new EndpointInfoBuilder("*", "exact:/foo").defaultMimeType(MediaType.JSON).build());
        final ServiceInfo barServiceInfo = services.get(BarClass.class.getName());
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
        public void barMethod(@Param String bar) {}
    }
}
