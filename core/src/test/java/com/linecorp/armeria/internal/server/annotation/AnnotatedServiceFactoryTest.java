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
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.collectDecorators;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.create;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.find;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceFactory.AnnotatedDecoratorAndOrder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
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
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.RateLimitingDecorator;
import com.linecorp.armeria.server.annotation.decorator.RateLimitingDecoratorFactoryFunction;

class AnnotatedServiceFactoryTest {

    private static final String HOME_PATH_PREFIX = "/home";
    private static final String ANNOTATED_DESCRIPTION = "This is a description from the annotation";

    @Test
    void ofNoOrdering() throws NoSuchMethodException {
        final List<AnnotatedDecoratorAndOrder> list =
                collectDecorators(TestClass.class,
                                  TestClass.class.getMethod("noOrdering"));
        assertThat(values(list)).containsExactly(Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 Decorator2.class);
        assertThat(orders(list)).containsExactly(0, 0, 0, 0);

        final LoggingDecorator info = (LoggingDecorator) list.get(1).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
        final LoggingDecorator trace = (LoggingDecorator) list.get(2).annotation();
        assertThat(trace.requestLogLevel()).isEqualTo(LogLevel.TRACE);
    }

    @Test
    void ofMethodScopeOrdering() throws NoSuchMethodException {
        final List<AnnotatedDecoratorAndOrder> list =
                collectDecorators(TestClass.class,
                                  TestClass.class.getMethod("methodScopeOrdering"));
        assertThat(values(list)).containsExactly(Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 RateLimitingDecoratorFactoryFunction.class,
                                                 Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 Decorator2.class);
        assertThat(orders(list)).containsExactly(0, 0, 0, 1, 2, 3);

        final LoggingDecorator info = (LoggingDecorator) list.get(1).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
        final RateLimitingDecorator limit = (RateLimitingDecorator) list.get(2).annotation();
        assertThat(limit.value()).isEqualTo(1);
        final LoggingDecorator trace = (LoggingDecorator) list.get(4).annotation();
        assertThat(trace.requestLogLevel()).isEqualTo(LogLevel.TRACE);
    }

    @Test
    void ofGlobalScopeOrdering() throws NoSuchMethodException {
        final List<AnnotatedDecoratorAndOrder> list =
                collectDecorators(TestClass.class,
                                  TestClass.class.getMethod("globalScopeOrdering"));
        assertThat(values(list)).containsExactly(LoggingDecoratorFactoryFunction.class,
                                                 Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 Decorator2.class);
        assertThat(orders(list)).containsExactly(-1, 0, 0, 1);

        final LoggingDecorator trace = (LoggingDecorator) list.get(0).annotation();
        assertThat(trace.requestLogLevel()).isEqualTo(LogLevel.TRACE);
        final LoggingDecorator info = (LoggingDecorator) list.get(2).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
    }

    @Test
    void ofUserDefinedRepeatableDecorator() throws NoSuchMethodException {
        final List<AnnotatedDecoratorAndOrder> list =
                collectDecorators(TestClass.class,
                                  TestClass.class.getMethod("userDefinedRepeatableDecorator"));
        assertThat(values(list)).containsExactly(Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 UserDefinedRepeatableDecoratorFactory.class,
                                                 Decorator2.class,
                                                 UserDefinedRepeatableDecoratorFactory.class);
        assertThat(orders(list)).containsExactly(0, 0, 1, 2, 3);

        final LoggingDecorator info = (LoggingDecorator) list.get(1).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
        final UserDefinedRepeatableDecorator udd1 = (UserDefinedRepeatableDecorator) list.get(2).annotation();
        assertThat(udd1.value()).isEqualTo(1);
        final UserDefinedRepeatableDecorator udd2 = (UserDefinedRepeatableDecorator) list.get(4).annotation();
        assertThat(udd2.value()).isEqualTo(2);
    }

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
        public void testMethod1(@Description(ANNOTATED_DESCRIPTION) @Param("param") String param) { }

        // Resource file from JavaDoc was already created
        @Get("/some/path")
        public void testMethod2(@Param("param") String param) { }
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

    private static List<Class<?>> values(List<AnnotatedDecoratorAndOrder> list) {
        return list.stream()
                   .map(AnnotatedDecoratorAndOrder::annotation)
                   .map(annotation -> {
                       if (annotation instanceof Decorator) {
                           return ((Decorator) annotation).value();
                       }
                       final DecoratorFactory factory =
                               annotation.annotationType().getAnnotation(DecoratorFactory.class);
                       if (factory != null) {
                           return factory.value();
                       }
                       throw new Error("Should not reach here.");
                   })
                   .collect(Collectors.toList());
    }

    private static List<Integer> orders(List<AnnotatedDecoratorAndOrder> list) {
        return list.stream().map(AnnotatedDecoratorAndOrder::order).collect(Collectors.toList());
    }

    static class Decorator1 implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    static class Decorator2 implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    static Stream<Method> getMethods(Class<?> clazz, Class<?> returnTypeClass) {
        final Method[] methods = clazz.getMethods();
        return Stream.of(methods).filter(method -> method.getReturnType() == returnTypeClass);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface UserDefinedRepeatableDecorators {
        UserDefinedRepeatableDecorator[] value();
    }

    @DecoratorFactory(UserDefinedRepeatableDecoratorFactory.class)
    @Repeatable(UserDefinedRepeatableDecorators.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface UserDefinedRepeatableDecorator {

        // To identify the decorator instance.
        int value();

        // For ordering.
        int order() default 0;
    }

    static class UserDefinedRepeatableDecoratorFactory
            implements DecoratorFactoryFunction<UserDefinedRepeatableDecorator> {
        @Override
        public Function<? super HttpService, ? extends HttpService> newDecorator(
                UserDefinedRepeatableDecorator parameter) {
            return service -> new SimpleDecoratingHttpService(service) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return service.serve(ctx, req);
                }
            };
        }
    }

    @Decorator(Decorator1.class)
    @LoggingDecorator(requestLogLevel = LogLevel.INFO)
    static class TestClass {

        @LoggingDecorator
        @Decorator(Decorator2.class)
        public String noOrdering() {
            return "";
        }

        @RateLimitingDecorator(1)
        @Decorator(value = Decorator1.class, order = 1)
        @LoggingDecorator(order = 2)
        @Decorator(value = Decorator2.class, order = 3)
        public String methodScopeOrdering() {
            return "";
        }

        @Decorator(value = Decorator2.class, order = 1)
        @LoggingDecorator(order = -1)
        public String globalScopeOrdering() {
            return "";
        }

        @UserDefinedRepeatableDecorator(value = 1, order = 1)
        @Decorator(value = Decorator2.class, order = 2)
        @UserDefinedRepeatableDecorator(value = 2, order = 3)
        public String userDefinedRepeatableDecorator() {
            return "";
        }
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
