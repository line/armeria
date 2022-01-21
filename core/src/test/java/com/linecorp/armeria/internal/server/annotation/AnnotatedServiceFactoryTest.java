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
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.create;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.find;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.List;
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
import com.linecorp.armeria.server.annotation.Trace;

class AnnotatedServiceFactoryTest {

    private static final String HOME_PATH_PREFIX = "/home";
    private static final String ANNOTATED_DESCRIPTION = "This is a description from the annotation";

    @Test
    void testFindAnnotatedServiceElementsWithPathPrefixAnnotation() {
        final Object object = new PathPrefixServiceObject();
        final List<AnnotatedServiceElement> elements =
                find("/", object, /* useBlockingTaskExecutor */ false,
                     ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

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
                     ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

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
                .map(method -> create("/", serviceObject, method, /* useBlockingTaskExecutor */ false,
                                      ImmutableList.of(), ImmutableList.of(), ImmutableList.of()))
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
                create("/", serviceObject, method, /* useBlockingTaskExecutor */ false,
                       ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
            }, method.getName()).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void testDescriptionLoadingPriority() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod1", String.class)
                                                                       .getParameters()[0];
        assertThat(AnnotatedServiceFactory.findDescription(parameter)).isEqualTo(ANNOTATED_DESCRIPTION);
    }

    @Test
    void testDescriptionLoadFromFile() throws NoSuchMethodException {
        final Parameter parameter = DescriptionAnnotatedTestClass.class.getMethod("testMethod2", String.class)
                                                                       .getParameters()[0];
        assertThat(AnnotatedServiceFactory.findDescription(parameter))
                .isEqualTo("This is a description from the properties file");
    }

    private static class DescriptionAnnotatedTestClass {
        // Resource file from JavaDoc was already created
        @Get("/some/path")
        public void testMethod1(@Description(ANNOTATED_DESCRIPTION) @Param("param") String param) {}

        // Resource file from JavaDoc was already created
        @Get("/some/path")
        public void testMethod2(@Param("param") String param) {}
    }

    private static List<AnnotatedServiceElement> getServiceElements(
            Object service, String methodName, HttpMethod httpMethod) {
        return getMethods(service.getClass(), HttpResponse.class)
                .filter(method -> method.getName().equals(methodName)).flatMap(
                        method -> {
                            final List<AnnotatedServiceElement> AnnotatedServices = create(
                                    "/", service, method, /* useBlockingTaskExecutor */ false,
                                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
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
