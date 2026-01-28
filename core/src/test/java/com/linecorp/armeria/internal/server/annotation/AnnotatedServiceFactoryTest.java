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
import static com.linecorp.armeria.internal.server.annotation.AnnotatedBeanFactoryRegistryTest.noopDependencyInjector;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.create;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.find;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.findDescription;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.findReturnDescription;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.findThrowsDescriptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.PathPrefix;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.ReturnDescription;
import com.linecorp.armeria.server.annotation.ThrowsDescription;
import com.linecorp.armeria.server.annotation.Trace;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.Markup;

class AnnotatedServiceFactoryTest {

    private static final String HOME_PATH_PREFIX = "/home";
    private static final String ANNOTATED_DESCRIPTION = "This is a description from the annotation";
    private static final String ANNOTATED_DESCRIPTION_SUPER = "This is a super description from the annotation";

    @Test
    void testFindAnnotatedServiceElementsWithPathPrefixAnnotation() {
        final Object object = new PathPrefixServiceObject();
        final List<AnnotatedServiceElement> elements =
                find("/", object, /* useBlockingTaskExecutor */ false,
                     ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), noopDependencyInjector, null);
        final List<String> paths = elements.stream()
                                           .map(AnnotatedServiceElement::route)
                                           .map(route -> route.paths().get(0))
                                           .collect(Collectors.toList());

        assertThat(paths).containsExactlyInAnyOrder(HOME_PATH_PREFIX + "/hello", HOME_PATH_PREFIX + '/');
    }

    @Test
    void testFindAnnotatedServiceElementsWithoutPathPrefixAnnotation() {
        final Object serviceObject = new ServiceObject();
        final List<AnnotatedServiceElement> elements =
                find(HOME_PATH_PREFIX, serviceObject, /* useBlockingTaskExecutor */ false,
                     ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), noopDependencyInjector, null);
        final List<String> paths = elements.stream()
                                           .map(AnnotatedServiceElement::route)
                                           .map(route -> route.paths().get(0))
                                           .collect(Collectors.toList());

        assertThat(paths).containsExactlyInAnyOrder(HOME_PATH_PREFIX + "/hello", HOME_PATH_PREFIX + '/');
    }

    @Test
    void testCreateAnnotatedServiceElementWithoutExplicitPathOnMethod() {
        final ServiceObjectWithoutPathOnAnnotatedMethod serviceObject =
                new ServiceObjectWithoutPathOnAnnotatedMethod();

        final List<Route> routes = Stream.of(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.PUT,
                                             HttpMethod.OPTIONS, HttpMethod.PATCH, HttpMethod.POST,
                                             HttpMethod.TRACE)
                                         .map(m -> Route.builder().path("/").methods(m).build())
                                         .collect(toImmutableList());

        final List<Route> actualRoutes = getMethods(ServiceObjectWithoutPathOnAnnotatedMethod.class,
                                                    HttpResponse.class)
                .map(method -> create("/", serviceObject, method, 0, false,
                                      ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                      noopDependencyInjector, null))
                .flatMap(Collection::stream)
                .map(AnnotatedServiceElement::route)
                .collect(toImmutableList());
        assertThat(actualRoutes).containsAll(routes);
    }

    @Test
    void testMultiPathSuccessGetMapping() {
        final List<AnnotatedServiceElement> getServiceElements =
                getServiceElements(new MultiPathSuccessService(), "getMapping", HttpMethod.GET);
        final Set<Route> routes = getServiceElements.stream().map(AnnotatedServiceElement::route).collect(
                Collectors.toSet());
        assertThat(routes).containsOnly(Route.builder().path("/getMapping").methods(HttpMethod.GET).build());
    }

    @Test
    void testMultiPathSuccessGetPostMapping() {
        final List<AnnotatedServiceElement> getServiceElements = getServiceElements(
                new MultiPathSuccessService(), "getPostMapping", HttpMethod.GET);
        final Set<Route> getRoutes = getServiceElements.stream().map(AnnotatedServiceElement::route)
                                                       .collect(Collectors.toSet());
        assertThat(getRoutes).containsOnly(Route.builder().path("/getMapping").methods(HttpMethod.GET).build());

        final List<AnnotatedServiceElement> postServiceElements = getServiceElements(
                new MultiPathSuccessService(), "getPostMapping", HttpMethod.POST);
        final Set<Route> postRoutes = postServiceElements.stream().map(AnnotatedServiceElement::route)
                                                         .collect(Collectors.toSet());
        assertThat(postRoutes).containsOnly(Route.builder().path("/postMapping").methods(HttpMethod.POST)
                                                 .build());
    }

    @Test
    void testMultiPathSuccessGetPostMappingByPath() {
        final List<AnnotatedServiceElement> getServiceElements = getServiceElements(
                new MultiPathSuccessService(), "getPostMappingByPath", HttpMethod.GET);
        final Set<Route> getRoutes = getServiceElements.stream().map(AnnotatedServiceElement::route)
                                                       .collect(Collectors.toSet());
        assertThat(getRoutes).containsOnly(Route.builder().path("/path").methods(HttpMethod.GET).build());

        final List<AnnotatedServiceElement> postServiceElements = getServiceElements(
                new MultiPathSuccessService(), "getPostMappingByPath", HttpMethod.POST);
        final Set<Route> postRoutes = postServiceElements.stream().map(AnnotatedServiceElement::route)
                                                         .collect(Collectors.toSet());
        assertThat(postRoutes).containsOnly(Route.builder().path("/path").methods(HttpMethod.POST).build());
    }

    @Test
    void testMultiPathAnnotations() {
        final List<AnnotatedServiceElement> getServiceElements = getServiceElements(
                new MultiPathSuccessService(), "multiPathAnnotations", HttpMethod.GET);
        final Set<Route> getRoutes = getServiceElements.stream().map(AnnotatedServiceElement::route)
                                                       .collect(Collectors.toSet());
        assertThat(getRoutes).containsOnly(Route.builder().path("/path1").methods(HttpMethod.GET).build(),
                                           Route.builder().path("/path2").methods(HttpMethod.GET).build());

        final List<AnnotatedServiceElement> postServiceElements = getServiceElements(
                new MultiPathSuccessService(), "multiPathAnnotations", HttpMethod.POST);
        final Set<Route> postRoutes = postServiceElements.stream().map(AnnotatedServiceElement::route)
                                                         .collect(Collectors.toSet());
        assertThat(postRoutes).containsOnly(Route.builder().path("/path1").methods(HttpMethod.POST).build(),
                                            Route.builder().path("/path2").methods(HttpMethod.POST).build());
    }

    @Test
    void testDuplicatePathAnnotations() {
        final List<AnnotatedServiceElement> getServiceElements = getServiceElements(
                new MultiPathSuccessService(), "duplicatePathAnnotations", HttpMethod.GET);
        assertThat(getServiceElements).hasSize(2);
        final Set<Route> getRoutes = getServiceElements.stream().map(AnnotatedServiceElement::route)
                                                       .collect(Collectors.toSet());
        assertThat(getRoutes).containsOnly(Route.builder().path("/path").methods(HttpMethod.GET).build(),
                                           Route.builder().path("/path").methods(HttpMethod.GET).build());

        final List<AnnotatedServiceElement> postServiceElements = getServiceElements(
                new MultiPathSuccessService(), "duplicatePathAnnotations", HttpMethod.POST);
        assertThat(getServiceElements).hasSize(2);
        final Set<Route> postRoutes = postServiceElements.stream().map(AnnotatedServiceElement::route)
                                                         .collect(Collectors.toSet());
        assertThat(postRoutes).containsOnly(Route.builder().path("/path").methods(HttpMethod.POST).build(),
                                            Route.builder().path("/path").methods(HttpMethod.POST).build());
    }

    @Test
    void testMultiPathFailingService() {
        final MultiPathFailingService serviceObject = new MultiPathFailingService();
        getMethods(MultiPathFailingService.class, HttpResponse.class).forEach(method -> {
            assertThatThrownBy(() -> {
                create("/", serviceObject, method, 0, false,
                       ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                       noopDependencyInjector, null);
            }, method.getName()).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void testDescriptionLoadingPriority() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod1", String.class)
                                                                       .getParameters()[0];
        final DescriptionInfo descriptionInfo = findDescription(parameter);
        assertThat(descriptionInfo.docString()).isEqualTo(ANNOTATED_DESCRIPTION);
    }

    @Test
    void testDescriptionLoadFromFile() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod2", String.class)
                                                                       .getParameters()[0];
        final DescriptionInfo descriptionInfo = findDescription(parameter);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a description from the properties file");
    }

    @Test
    void testParameterDescriptionLoad() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod3",
                                                                                  String.class, String.class)
                                                                       .getParameters()[0];
        final DescriptionInfo descriptionInfo = findDescription(parameter);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a description from the annotation");
    }

    @Test
    void testParameterDescriptionLoadByParentClass() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod4",
                                                                                  String.class, String.class)
                                                                       .getParameters()[0];
        final DescriptionInfo descriptionInfo = findDescription(parameter);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a super description from the annotation");
    }

    @Test
    void testParameterDescriptionLoadByChildClass() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod5",
                                                                                  String.class, String.class)
                                                                       .getParameters()[0];
        final DescriptionInfo descriptionInfo = findDescription(parameter);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a description from the annotation");
    }

    @Test
    void testMethodDescriptionLoad() throws NoSuchMethodException {
        final Method method = DescriptionAnnotatedTestClass.class.getMethod("testMethod3",
                                                                            String.class, String.class);
        final DescriptionInfo descriptionInfo = findDescription(method);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a description from the annotation");
    }

    @Test
    void testMethodDescriptionLoadByParentClass() throws NoSuchMethodException {
        final Method method = DescriptionAnnotatedTestClass.class.getMethod("testMethod4",
                                                                            String.class, String.class);
        final DescriptionInfo descriptionInfo = findDescription(method);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a super description from the annotation");
    }

    @Test
    void testMethodDescriptionLoadByChildClass() throws NoSuchMethodException {
        final Method method = DescriptionAnnotatedTestClass.class.getMethod("testMethod5", String.class,
                                                                            String.class);
        final DescriptionInfo descriptionInfo = findDescription(method);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a description from the annotation");
    }

    @Test
    void testClassDescriptionLoadByParentClass() {
        final Class<DescriptionAnnotatedTestClass> clazz = DescriptionAnnotatedTestClass.class;
        final DescriptionInfo descriptionInfo = findDescription(clazz);
        assertThat(descriptionInfo.docString())
                .isEqualTo("This is a super description from the annotation");
    }

    @Test
    void testFindDescription() throws NoSuchMethodException {
        // Test method with @Description annotation
        final Method methodWithDesc = DescriptionTestClass.class.getMethod("methodWithDescription");
        final DescriptionInfo descInfo = findDescription(methodWithDesc);
        assertThat(descInfo.docString()).isEqualTo("Method description");
        assertThat(descInfo.markup()).isEqualTo(Markup.NONE);

        // Test method with @Description annotation with markup
        final Method methodWithMarkdown = DescriptionTestClass.class.getMethod("methodWithMarkdownDescription");
        final DescriptionInfo markdownInfo = findDescription(methodWithMarkdown);
        assertThat(markdownInfo.docString()).isEqualTo("**Markdown** description");
        assertThat(markdownInfo.markup()).isEqualTo(Markup.MARKDOWN);

        // Test method without @Description annotation
        final Method methodWithoutDesc = DescriptionTestClass.class.getMethod("methodWithoutDescription");
        final DescriptionInfo emptyInfo = findDescription(methodWithoutDesc);
        assertThat(emptyInfo.docString()).isEmpty();

        // Test method with @Description with empty string
        final Method methodWithEmptyDesc = DescriptionTestClass.class.getMethod("methodWithEmptyDescription");
        final DescriptionInfo emptyStringInfo = findDescription(methodWithEmptyDesc);
        assertThat(emptyStringInfo.docString()).isEmpty();
    }

    @Test
    void testFindReturnDescription() throws NoSuchMethodException {
        // Test method with @ReturnDescription annotation
        final Method methodWithReturn =
                ReturnDescriptionTestClass.class.getMethod("methodWithReturnDescription");
        final DescriptionInfo returnInfo = findReturnDescription(methodWithReturn);
        assertThat(returnInfo.docString()).isEqualTo("The user name");
        assertThat(returnInfo.markup()).isEqualTo(Markup.NONE);

        // Test method with @ReturnDescription annotation with markup
        final Method methodWithMarkdown =
                ReturnDescriptionTestClass.class.getMethod("methodWithMarkdownReturn");
        final DescriptionInfo markdownInfo = findReturnDescription(methodWithMarkdown);
        assertThat(markdownInfo.docString()).isEqualTo("**bold** return value");
        assertThat(markdownInfo.markup()).isEqualTo(Markup.MARKDOWN);

        // Test method without @ReturnDescription annotation
        final Method methodWithoutReturn =
                ReturnDescriptionTestClass.class.getMethod("methodWithoutReturnDescription");
        final DescriptionInfo emptyInfo = findReturnDescription(methodWithoutReturn);
        assertThat(emptyInfo.docString()).isEmpty();

        // Test method with @ReturnDescription with empty string
        final Method methodWithEmptyReturn =
                ReturnDescriptionTestClass.class.getMethod("methodWithEmptyReturnDescription");
        final DescriptionInfo emptyStringInfo = findReturnDescription(methodWithEmptyReturn);
        assertThat(emptyStringInfo.docString()).isEmpty();
    }

    @Test
    void testFindThrowsDescriptions() throws NoSuchMethodException {
        // Test method with @ThrowsDescription annotations
        final Method methodWithThrows =
                ThrowsDescriptionTestClass.class.getMethod("methodWithThrowsDescriptions");
        final Map<Class<? extends Throwable>, DescriptionInfo> throwsInfo =
                findThrowsDescriptions(methodWithThrows);
        assertThat(throwsInfo).hasSize(2);
        assertThat(throwsInfo.get(IllegalArgumentException.class).docString())
                .isEqualTo("If the argument is invalid");
        assertThat(throwsInfo.get(IllegalStateException.class).docString())
                .isEqualTo("If the state is wrong");

        // Test method with @ThrowsDescription annotation with markup
        final Method methodWithMarkdown =
                ThrowsDescriptionTestClass.class.getMethod("methodWithMarkdownThrows");
        final Map<Class<? extends Throwable>, DescriptionInfo> markdownThrowsInfo =
                findThrowsDescriptions(methodWithMarkdown);
        assertThat(markdownThrowsInfo).hasSize(1);
        assertThat(markdownThrowsInfo.get(RuntimeException.class).docString())
                .isEqualTo("*runtime error*");
        assertThat(markdownThrowsInfo.get(RuntimeException.class).markup()).isEqualTo(Markup.MARKDOWN);

        // Test method without @ThrowsDescription annotation
        final Method methodWithoutThrows =
                ThrowsDescriptionTestClass.class.getMethod("methodWithoutThrowsDescription");
        final Map<Class<? extends Throwable>, DescriptionInfo> emptyThrowsInfo =
                findThrowsDescriptions(methodWithoutThrows);
        assertThat(emptyThrowsInfo).isEmpty();

        // Test method with @ThrowsDescription but no description specified (uses default)
        final Method methodWithUnspecifiedThrows =
                ThrowsDescriptionTestClass.class.getMethod("methodWithUnspecifiedThrowsDescription");
        final Map<Class<? extends Throwable>, DescriptionInfo> unspecifiedThrowsInfo =
                findThrowsDescriptions(methodWithUnspecifiedThrows);
        assertThat(unspecifiedThrowsInfo).hasSize(1);
        assertThat(unspecifiedThrowsInfo.get(NullPointerException.class).docString()).isEmpty();

        // Test method with @ThrowsDescription with empty string description
        final Method methodWithEmptyStringThrows =
                ThrowsDescriptionTestClass.class.getMethod("methodWithEmptyStringThrowsDescription");
        final Map<Class<? extends Throwable>, DescriptionInfo> emptyStringThrowsInfo =
                findThrowsDescriptions(methodWithEmptyStringThrows);
        assertThat(emptyStringThrowsInfo).hasSize(1);
        assertThat(emptyStringThrowsInfo.get(ArithmeticException.class).docString()).isEmpty();
    }

    private static class ReturnDescriptionTestClass {
        @Get("/test1")
        @ReturnDescription("The user name")
        public String methodWithReturnDescription() {
            return "";
        }

        @Get("/test2")
        @ReturnDescription(value = "**bold** return value", markup = Markup.MARKDOWN)
        public String methodWithMarkdownReturn() {
            return "";
        }

        @Get("/test3")
        public String methodWithoutReturnDescription() {
            return "";
        }

        @Get("/test4")
        @ReturnDescription("")
        public String methodWithEmptyReturnDescription() {
            return "";
        }
    }

    private static class ThrowsDescriptionTestClass {
        @Get("/test1")
        @ThrowsDescription(value = IllegalArgumentException.class, description = "If the argument is invalid")
        @ThrowsDescription(value = IllegalStateException.class, description = "If the state is wrong")
        public void methodWithThrowsDescriptions() {}

        @Get("/test2")
        @ThrowsDescription(value = RuntimeException.class,
                           description = "*runtime error*", markup = Markup.MARKDOWN)
        public void methodWithMarkdownThrows() {}

        @Get("/test3")
        public void methodWithoutThrowsDescription() {}

        @Get("/test4")
        @ThrowsDescription(NullPointerException.class)
        public void methodWithUnspecifiedThrowsDescription() {}

        @Get("/test5")
        @ThrowsDescription(value = ArithmeticException.class, description = "")
        public void methodWithEmptyStringThrowsDescription() {}
    }

    private static class DescriptionTestClass {
        @Get("/test1")
        @Description("Method description")
        public void methodWithDescription() {}

        @Get("/test2")
        @Description(value = "**Markdown** description", markup = Markup.MARKDOWN)
        public void methodWithMarkdownDescription() {}

        @Get("/test3")
        public void methodWithoutDescription() {}

        @Get("/test4")
        @Description("")
        public void methodWithEmptyDescription() {}
    }

    @Description(ANNOTATED_DESCRIPTION_SUPER)
    private interface DescriptionAnnotatedTestInterface {
        void testMethod3(String param1, String param2);

        @Description(ANNOTATED_DESCRIPTION_SUPER)
        void testMethod4(@Description(ANNOTATED_DESCRIPTION_SUPER) String param1, String param2);

        @Description(ANNOTATED_DESCRIPTION_SUPER)
        void testMethod5(@Description(ANNOTATED_DESCRIPTION_SUPER) String param1, String param2);
    }

    private static class DescriptionAnnotatedTestClass implements DescriptionAnnotatedTestInterface {
        // Resource file from JavaDoc was already created
        @Get("/some/path")
        public void testMethod1(@Description(ANNOTATED_DESCRIPTION) @Param("param") String param) {}

        // Resource file from JavaDoc was already created
        @Get("/some/path")
        public void testMethod2(@Param("param") String param) {}

        @Override
        @Description(ANNOTATED_DESCRIPTION)
        @Get("/some/path")
        public void testMethod3(@Description(ANNOTATED_DESCRIPTION) @Param("param") String param1,
                                String param2) {}

        @Override
        @Get("/some/path")
        public void testMethod4(@Param("param") String param1, String param2) {}

        @Override
        @Description(ANNOTATED_DESCRIPTION)
        @Get("/some/path")
        public void testMethod5(@Description(ANNOTATED_DESCRIPTION) @Param("param") String param1,
                                String param2) {}
    }

    private static List<AnnotatedServiceElement> getServiceElements(
            Object service, String methodName, HttpMethod httpMethod) {
        return getMethods(service.getClass(), HttpResponse.class)
                .filter(method -> method.getName().equals(methodName)).flatMap(
                        method -> {
                            final List<AnnotatedServiceElement> AnnotatedServices = create(
                                    "/", service, method, 0, false,
                                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                    noopDependencyInjector, null);
                            return AnnotatedServices.stream();
                        }
                )
                .filter(element -> element.route().methods().contains(httpMethod))
                .collect(Collectors.toList());
    }

    static Stream<Method> getMethods(Class<?> clazz, Class<?> returnTypeClass) {
        final Method[] methods = clazz.getMethods();
        return Stream.of(methods).filter(method -> method.getReturnType() == returnTypeClass);
    }

    @PathPrefix(HOME_PATH_PREFIX)
    static class PathPrefixServiceObject {

        @Get("/hello")
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/")
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class ServiceObject {

        @Get("/hello")
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/")
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    @PathPrefix("/")
    static class ServiceObjectWithoutPathOnAnnotatedMethod {

        @Post
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Head
        public HttpResponse head() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Put
        public HttpResponse put() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Delete
        public HttpResponse delete() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Options
        public HttpResponse options() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Patch
        public HttpResponse patch() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Trace
        public HttpResponse trace() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class MultiPathSuccessService {

        @Get("/getMapping")
        public HttpResponse getMapping() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get("/getMapping")
        @Post("/postMapping")
        public HttpResponse getPostMapping() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get
        @Post
        @Path("/path")
        public HttpResponse getPostMappingByPath() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get
        @Post
        @Path("/path1")
        @Path("/path2")
        public HttpResponse multiPathAnnotations() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get
        @Post
        @Path("/path")
        @Path("/path")
        public HttpResponse duplicatePathAnnotations() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    static class MultiPathFailingService {

        @Get("/get")
        @Path("/path")
        public HttpResponse getPathMapping() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Get("/get")
        @Post
        @Path("/path")
        public HttpResponse noPostMappingAndGetPathMapping() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Path("/path")
        public HttpResponse pathMapping() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
