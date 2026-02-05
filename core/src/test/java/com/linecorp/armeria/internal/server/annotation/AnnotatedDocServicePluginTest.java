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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RouteBuilder;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ReturnDescription;
import com.linecorp.armeria.server.annotation.ThrowsDescription;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeSignature;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ParamInfo;
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

        Set<String> methodNames = methods(services.get(FOO_NAME)).keySet();
        assertThat(methodNames).containsOnlyOnce("foo2Method");

        // 3-1. Include serviceName specified.
        include = DocServiceFilter.ofServiceName(FOO_NAME);
        // Set the exclude to the default.
        exclude = (plugin, service, method) -> false;
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methodNames = methods(services.get(FOO_NAME)).keySet();
        assertThat(methodNames).containsExactlyInAnyOrder("fooMethod", "foo2Method");

        // 3-2. Include methodName specified.
        include = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methodNames = methods(services.get(FOO_NAME)).keySet();
        assertThat(methodNames).containsOnlyOnce("fooMethod");

        // 4-1. Include and exclude specified.
        include = DocServiceFilter.ofServiceName(FOO_NAME);
        exclude = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        services = services(include, exclude, new FooClass());
        assertThat(services).containsOnlyKeys(FOO_NAME);

        methodNames = methods(services.get(FOO_NAME)).keySet();
        assertThat(methodNames).containsOnlyOnce("foo2Method");

        // 4-2. Include and exclude specified.
        include = DocServiceFilter.ofMethodName(FOO_NAME, "fooMethod");
        exclude = DocServiceFilter.ofServiceName(FOO_NAME);
        services = services(include, exclude, new FooClass(), new BarClass());
        assertThat(services.size()).isZero();
    }

    @Test
    void testMultiPath() {
        final Map<String, ServiceInfo> serviceInfos = services(new MultiPathClass());
        final Map<String, MethodInfo> methodInfos = methods(serviceInfos.get(MultiPathClass.class.getName()));
        final Set<String> paths = methodInfos.get("multiGet").endpoints()
                                         .stream().map(EndpointInfo::pathMapping)
                                         .collect(toImmutableSet());
        assertThat(paths).containsOnly("exact:/path1", "exact:/path2");
    }

    @Test
    void testDocStrings() {
        // Test all docstring extraction scenarios in a single comprehensive test
        final Map<String, DescriptionInfo> actualDocStrings = loadDocStrings(
                new DescriptionAnnotatedClass(),
                new EmptyDescriptionClass(),
                new JavadocDescriptionClass(),
                new AnnotationPrecedenceClass());

        final Map<String, DescriptionInfo> expectedDocStrings = ImmutableMap.<String, DescriptionInfo>builder()
                // === DescriptionAnnotatedClass: @Description, @ReturnDescription, @ThrowsDescription ===
                .put(DescriptionAnnotatedClass.class.getName(),
                     DescriptionInfo.of("A service for user operations"))
                .put(DescriptionAnnotatedClass.class.getName() + "/getUser",
                     DescriptionInfo.of("Gets the user name by ID"))
                .put(DescriptionAnnotatedClass.class.getName() + "/getUser:param/id",
                     DescriptionInfo.of("The user ID"))
                .put(DescriptionAnnotatedClass.class.getName() + "/getUser:return",
                     DescriptionInfo.of("The user name"))
                .put(DescriptionAnnotatedClass.class.getName() +
                     "/getUser:throws/java.lang.IllegalArgumentException",
                     DescriptionInfo.of("If the ID is invalid"))
                .put(DescriptionAnnotatedClass.class.getName() +
                     "/getUser:throws/IllegalArgumentException",
                     DescriptionInfo.of("If the ID is invalid"))
                .put(DescriptionAnnotatedClass.class.getName() +
                     "/getUser:throws/java.lang.IllegalStateException",
                     DescriptionInfo.of("If the user is not found"))
                .put(DescriptionAnnotatedClass.class.getName() +
                     "/getUser:throws/IllegalStateException",
                     DescriptionInfo.of("If the user is not found"))
                .put(DescriptionAnnotatedClass.class.getName() + "/simpleMethod:return",
                     DescriptionInfo.of("Simple return value"))
                // Note: noDescriptionMethod and exceptionOnlyMethod have no docstrings
                // Note: EmptyDescriptionClass has no docstrings (all empty)

                // === JavadocDescriptionClass: Javadoc from properties files ===
                .put(JavadocDescriptionClass.class.getName() + "/javadocMethod",
                     DescriptionInfo.of("Method description from Javadoc"))
                .put(JavadocDescriptionClass.class.getName() + "/javadocMethod:param/param1",
                     DescriptionInfo.of("Parameter from Javadoc"))
                .put(JavadocDescriptionClass.class.getName() + "/javadocMethod:return",
                     DescriptionInfo.of("Return value from Javadoc"))
                .put(JavadocDescriptionClass.class.getName() + "/javadocMethod:throws/IllegalArgumentException",
                     DescriptionInfo.of("Exception from Javadoc"))

                // === AnnotationPrecedenceClass: Annotations take precedence over properties ===
                .put(AnnotationPrecedenceClass.class.getName() + "/precedenceMethod",
                     DescriptionInfo.of("Method from annotation"))
                .put(AnnotationPrecedenceClass.class.getName() + "/precedenceMethod:param/param1",
                     DescriptionInfo.of("Parameter from annotation"))
                .put(AnnotationPrecedenceClass.class.getName() + "/precedenceMethod:return",
                     DescriptionInfo.of("Return from annotation"))
                // Annotation overrides both full and simple class name keys
                .put(AnnotationPrecedenceClass.class.getName() +
                     "/precedenceMethod:throws/java.lang.IllegalArgumentException",
                     DescriptionInfo.of("Exception from annotation"))
                .put(AnnotationPrecedenceClass.class.getName() +
                     "/precedenceMethod:throws/IllegalArgumentException",
                     DescriptionInfo.of("Exception from annotation"))
                .build();

        assertThat(actualDocStrings).isEqualTo(expectedDocStrings);
    }

    @Test
    void exceptionTypeSignaturesWithoutDescription() {
        // Verify that exception type signatures are captured even when no description is provided
        final Map<String, ServiceInfo> serviceInfos = services(
                new DescriptionAnnotatedClass(), new EmptyDescriptionClass());

        // DescriptionAnnotatedClass.exceptionOnlyMethod - @ThrowsDescription without description
        final String descClassName = DescriptionAnnotatedClass.class.getName();
        final MethodInfo exceptionOnlyMethod =
                methods(serviceInfos.get(descClassName)).get("exceptionOnlyMethod");
        assertThat(exceptionOnlyMethod.exceptionTypeSignatures()).hasSize(1);
        assertThat(exceptionOnlyMethod.exceptionTypeSignatures().iterator().next().signature())
                .contains("NullPointerException");

        // EmptyDescriptionClass.emptyDescriptionsMethod - @ThrowsDescription with empty description
        final String emptyClassName = EmptyDescriptionClass.class.getName();
        final MethodInfo emptyMethod = methods(serviceInfos.get(emptyClassName)).get("emptyDescriptionsMethod");
        assertThat(emptyMethod.exceptionTypeSignatures()).hasSize(1);
        assertThat(emptyMethod.exceptionTypeSignatures().iterator().next().signature())
                .contains("RuntimeException");
    }

    @Test
    void structAndFieldDescriptions() {
        // Test that @Description annotations on struct classes and fields are properly extracted
        // through the full pipeline (generateSpecification)
        final ServiceSpecification specification = generateSpecification(new BarClass());

        // Get structs from the specification
        final Map<String, StructInfo> structs = specification.structs().stream()
                .collect(toImmutableMap(StructInfo::name, Function.identity()));

        // Verify CompositeBean struct description
        final StructInfo compositeBean = structs.get(CompositeBean.class.getName());
        assertThat(compositeBean).isNotNull();
        assertThat(compositeBean.descriptionInfo().docString())
                .isEqualTo("A composite bean containing multiple request beans");

        // Verify CompositeBean fields
        final Map<String, FieldInfo> compositeBeanFields = compositeBean.fields().stream()
                .collect(toImmutableMap(FieldInfo::name, Function.identity()));
        assertThat(compositeBeanFields.get("bean1").descriptionInfo().docString())
                .isEqualTo("The first request bean");
        assertThat(compositeBeanFields.get("bean2").descriptionInfo().docString())
                .isEqualTo("The second request bean");

        // Verify RequestBean1 struct description
        final StructInfo requestBean1 = structs.get(RequestBean1.class.getName());
        assertThat(requestBean1).isNotNull();
        assertThat(requestBean1.descriptionInfo().docString())
                .isEqualTo("Request bean with sequence number and user ID");

        // Verify RequestBean1 fields
        final Map<String, FieldInfo> requestBean1Fields = requestBean1.fields().stream()
                .collect(toImmutableMap(FieldInfo::name, Function.identity()));
        assertThat(requestBean1Fields.get("seqNum").descriptionInfo().docString())
                .isEqualTo("The sequence number");
        assertThat(requestBean1Fields.get("uid").descriptionInfo().docString())
                .isEqualTo("The user ID");

        // Verify RequestBean2 struct description
        final StructInfo requestBean2 = structs.get(RequestBean2.class.getName());
        assertThat(requestBean2).isNotNull();
        assertThat(requestBean2.descriptionInfo().docString())
                .isEqualTo("Request bean with foo field");

        // Verify RequestBean2 fields
        final Map<String, FieldInfo> requestBean2Fields = requestBean2.fields().stream()
                .collect(toImmutableMap(FieldInfo::name, Function.identity()));
        assertThat(requestBean2Fields.get("foo").descriptionInfo().docString())
                .isEqualTo("The foo field");
        assertThat(requestBean2Fields.get("insideBean").descriptionInfo().docString())
                .isEqualTo("The inside bean");

        // Verify InsideBean struct description
        final StructInfo insideBean = structs.get(RequestBean2.InsideBean.class.getName());
        assertThat(insideBean).isNotNull();
        assertThat(insideBean.descriptionInfo().docString())
                .isEqualTo("Inside bean nested in RequestBean2");

        // Verify InsideBean fields
        final Map<String, FieldInfo> insideBeanFields = insideBean.fields().stream()
                .collect(toImmutableMap(FieldInfo::name, Function.identity()));
        assertThat(insideBeanFields.get("name").descriptionInfo().docString())
                .isEqualTo("The name field");
    }

    private static void checkFooService(ServiceInfo fooServiceInfo) {
        assertThat(fooServiceInfo.exampleHeaders()).isEmpty();
        final Map<String, MethodInfo> methods = methods(fooServiceInfo);
        assertThat(methods).containsKeys("fooMethod", "foo2Method");

        final MethodInfo fooMethod = methods.get("fooMethod");
        assertThat(fooMethod.exampleHeaders()).isEmpty();
        assertThat(fooMethod.exampleRequests()).isEmpty();

        assertThat(fooMethod.parameters()).hasSize(2);
        assertThat(fooMethod.parameters()).containsExactlyInAnyOrder(
                ParamInfo.builder("foo", STRING).requirement(REQUIRED)
                         .location(QUERY)
                         .build(),
                ParamInfo.builder("foo1", LONG).requirement(REQUIRED)
                         .location(HEADER)
                         .build());

        assertThat(fooMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(fooMethod.endpoints()).containsExactly(EndpointInfo.builder("*", "exact:/foo")
                                                                      .defaultMimeType(MediaType.JSON)
                                                                      .build());
    }

    private static void checkBarService(ServiceInfo barServiceInfo) {
        assertThat(barServiceInfo.exampleHeaders()).isEmpty();
        final Map<String, MethodInfo> methods = methods(barServiceInfo);
        assertThat(methods).containsKeys("barMethod");

        final MethodInfo barMethod = methods.get("barMethod");
        assertThat(barMethod.exampleHeaders()).isEmpty();
        assertThat(barMethod.exampleRequests()).isEmpty();
        assertThat(barMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(barMethod.endpoints()).containsExactly(EndpointInfo.builder("*", "exact:/bar")
                                                                      .defaultMimeType(MediaType.JSON)
                                                                      .build());

        final ParamInfo bar = ParamInfo.builder("bar", STRING).requirement(REQUIRED)
                                       .location(QUERY)
                                       .build();
        final List<ParamInfo> paramInfos = barMethod.parameters();
        assertThat(paramInfos).hasSize(2);
        assertThat(paramInfos).contains(bar);
        final Optional<ParamInfo> compositeBean =
                paramInfos.stream()
                          .filter(paramInfo -> "compositeBean".equals(paramInfo.name()))
                          .findFirst();
        assertThat(compositeBean).isPresent();
        final StructInfo expected = new StructInfo(
                CompositeBean.class.getName(),
                ImmutableList.of(
                        createBean("bean1", RequestBean1.class, "The first request bean"),
                        createBean("bean2", RequestBean2.class, "The second request bean")),
                DescriptionInfo.of("A composite bean containing multiple request beans"));
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

    private static Map<String, MethodInfo> methods(ServiceInfo serviceInfo) {
        return serviceInfo.methods().stream()
                          .collect(toImmutableMap(MethodInfo::name, Function.identity()));
    }

    private static Map<String, DescriptionInfo> loadDocStrings(Object... services) {
        final ServerBuilder builder = Server.builder();
        Arrays.stream(services).forEach(builder::annotatedService);
        final Server server = builder.build();
        return plugin.loadDocStrings(ImmutableSet.copyOf(server.serviceConfigs()));
    }

    private static ServiceSpecification generateSpecification(Object... services) {
        final ServerBuilder builder = Server.builder();
        Arrays.stream(services).forEach(builder::annotatedService);
        final Server server = builder.build();
        final DocServiceFilter include = (plugin, service, method) -> true;
        final DocServiceFilter exclude = (plugin, service, method) -> false;
        return plugin.generateSpecification(ImmutableSet.copyOf(server.serviceConfigs()),
                                            unifyFilter(include, exclude), typeDescriptor -> null);
    }

    static ParamInfo compositeBean() {
        return ParamInfo.builder("compositeBean",
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

    private static FieldInfo createBean(String name, Class<?> type, String description) {
        return FieldInfo.builder(name, TypeSignature.ofStruct(type))
                        .requirement(REQUIRED)
                        .descriptionInfo(DescriptionInfo.of(description))
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

    @Description("A service for user operations")
    private static class DescriptionAnnotatedClass {
        @Get("/user")
        @Description("Gets the user name by ID")
        @ReturnDescription("The user name")
        @ThrowsDescription(value = IllegalArgumentException.class, description = "If the ID is invalid")
        @ThrowsDescription(value = IllegalStateException.class, description = "If the user is not found")
        public String getUser(@Param @Description("The user ID") String id) {
            return "user";
        }

        @Get("/simple")
        @ReturnDescription("Simple return value")
        public String simpleMethod() {
            return "simple";
        }

        @Get("/no-description")
        public String noDescriptionMethod() {
            return "no description";
        }

        // Test case: exception only (no description specified)
        @Get("/exception-only")
        @ThrowsDescription(NullPointerException.class)
        public void exceptionOnlyMethod() {}
    }

    private static class EmptyDescriptionClass {
        @Get("/empty-descriptions")
        @Description("")
        @ReturnDescription("")
        @ThrowsDescription(value = RuntimeException.class, description = "")
        public String emptyDescriptionsMethod() {
            return "empty";
        }
    }

    // Test class for verifying loadDocStrings() loads Javadoc from properties file
    // The Javadoc descriptions are provided via the properties file, not annotations
    private static class JavadocDescriptionClass {
        @Get("/javadoc")
        public String javadocMethod(@Param String param1) {
            return "javadoc";
        }
    }

    // Test class for verifying that annotations take precedence over properties file
    // Both annotations and properties file define descriptions for this class
    private static class AnnotationPrecedenceClass {
        @Get("/precedence")
        @Description("Method from annotation")
        @ReturnDescription("Return from annotation")
        @ThrowsDescription(value = IllegalArgumentException.class, description = "Exception from annotation")
        public String precedenceMethod(@Param @Description("Parameter from annotation") String param1) {
            return "precedence";
        }
    }

    @Description("A composite bean containing multiple request beans")
    static class CompositeBean {
        @RequestObject
        @Description("The first request bean")
        private RequestBean1 bean1;

        @RequestObject
        @Description("The second request bean")
        private RequestBean2 bean2;
    }

    @Description("Request bean with sequence number and user ID")
    static class RequestBean1 {
        @Nullable
        @JsonProperty
        @Param
        @Description("The sequence number")
        private Long seqNum;

        @JsonProperty
        @Description("The user ID")
        private String uid;

        @Nullable
        private String notPopulatedStr;

        RequestBean1(@Header String uid) {
            this.uid = uid;
        }
    }

    @Description("Request bean with foo field")
    static class RequestBean2 {

        @JsonProperty
        @Param
        @Description("The foo field")
        private String foo;

        @RequestObject
        @Description("The inside bean")
        private InsideBean insideBean;

        @Description("Inside bean nested in RequestBean2")
        static class InsideBean {
            @JsonProperty
            @Param
            @Description("The name field")
            private String name;
        }
    }
}
