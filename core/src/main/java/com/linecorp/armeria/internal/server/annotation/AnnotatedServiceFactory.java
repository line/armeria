/*
 *  Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.server.RouteUtil.ensureAbsolutePath;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withModifier;
import static org.reflections.ReflectionUtils.withName;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.NoParameterException;
import com.linecorp.armeria.internal.server.annotation.AnnotationUtil.FindOption;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.AdditionalTrailer;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.MatchesHeader;
import com.linecorp.armeria.server.annotation.MatchesParam;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.PathPrefix;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.annotation.Trace;

/**
 * Builds a list of {@link AnnotatedService}s from an {@link Object}.
 * This class is not supposed to be used by a user. Please check out the documentation
 * <a href="https://line.github.io/armeria/docs/server-annotated-service">
 * Annotated HTTP Service</a> to use {@link AnnotatedService}.
 */
public final class AnnotatedServiceFactory {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedServiceFactory.class);

    /**
     * An instance map for reusing converters, exception handlers and decorators.
     */
    private static final ConcurrentMap<Class<?>, Object> instanceCache = new ConcurrentHashMap<>();

    /**
     * A default {@link ExceptionHandlerFunction}.
     */
    private static final ExceptionHandlerFunction defaultExceptionHandler = new DefaultExceptionHandler();

    /**
     * Mapping from HTTP method annotation to {@link HttpMethod}, like following.
     * <ul>
     *   <li>{@link Options} -> {@link HttpMethod#OPTIONS}
     *   <li>{@link Get} -> {@link HttpMethod#GET}
     *   <li>{@link Head} -> {@link HttpMethod#HEAD}
     *   <li>{@link Post} -> {@link HttpMethod#POST}
     *   <li>{@link Put} -> {@link HttpMethod#PUT}
     *   <li>{@link Patch} -> {@link HttpMethod#PATCH}
     *   <li>{@link Delete} -> {@link HttpMethod#DELETE}
     *   <li>{@link Trace} -> {@link HttpMethod#TRACE}
     * </ul>
     */
    private static final Map<Class<?>, HttpMethod> HTTP_METHOD_MAP =
            ImmutableMap.<Class<?>, HttpMethod>builder()
                    .put(Options.class, HttpMethod.OPTIONS)
                    .put(Get.class, HttpMethod.GET)
                    .put(Head.class, HttpMethod.HEAD)
                    .put(Post.class, HttpMethod.POST)
                    .put(Put.class, HttpMethod.PUT)
                    .put(Patch.class, HttpMethod.PATCH)
                    .put(Delete.class, HttpMethod.DELETE)
                    .put(Trace.class, HttpMethod.TRACE)
                    .build();

    /**
     * Returns the list of {@link AnnotatedService} defined by {@link Path} and HTTP method annotations
     * from the specified {@code object}, {@link RequestConverterFunction}s, {@link ResponseConverterFunction}s,
     * {@link ExceptionHandlerFunction}s and {@link AnnotatedServiceExtensions}.
     */
    public static List<AnnotatedServiceElement> find(
            String pathPrefix, Object object,
            List<RequestConverterFunction> requestConverterFunctions,
            List<ResponseConverterFunction> responseConverterFunctions,
            List<ExceptionHandlerFunction> exceptionHandlerFunctions) {
        final List<Method> methods = requestMappingMethods(object);
        return methods.stream()
                      .flatMap((Method method) ->
                                       create(pathPrefix, object, method, requestConverterFunctions,
                                              responseConverterFunctions, exceptionHandlerFunctions).stream())
                      .collect(toImmutableList());
    }

    private static HttpStatus defaultResponseStatus(Method method) {
        final StatusCode statusCodeAnnotation = AnnotationUtil.findFirst(method, StatusCode.class);
        if (statusCodeAnnotation == null) {
            // Set a default HTTP status code for a response depending on the return type of the method.
            final Class<?> returnType = method.getReturnType();
            return returnType == Void.class ||
                   returnType == void.class ? HttpStatus.NO_CONTENT : HttpStatus.OK;
        }

        final int statusCode = statusCodeAnnotation.value();
        checkArgument(statusCode >= 0,
                      "invalid HTTP status code: %s (expected: >= 0)", statusCode);
        return HttpStatus.valueOf(statusCode);
    }

    private static <T extends Annotation> void setAdditionalHeader(HttpHeadersBuilder headers,
                                                                   AnnotatedElement element,
                                                                   String clsAlias,
                                                                   String elementAlias,
                                                                   String level,
                                                                   Class<T> annotation,
                                                                   Function<T, String> nameGetter,
                                                                   Function<T, String[]> valueGetter) {
        requireNonNull(headers, "headers");
        requireNonNull(element, "element");
        requireNonNull(level, "level");

        final Set<String> addedHeaderSets = new HashSet<>();
        AnnotationUtil.findAll(element, annotation).forEach(header -> {
            final String name = nameGetter.apply(header);
            final String[] value = valueGetter.apply(header);

            if (addedHeaderSets.contains(name)) {
                logger.warn("The additional {} named '{}' at '{}' is set at the same {} level already;" +
                            "ignoring.",
                            clsAlias, name, elementAlias, level);
                return;
            }
            headers.set(HttpHeaderNames.of(name), value);
            addedHeaderSets.add(name);
        });
    }

    /**
     * Returns a list of {@link AnnotatedService} instances. A single {@link AnnotatedService} is
     * created per each {@link Route} associated with the {@code method}.
     */
    @VisibleForTesting
    static List<AnnotatedServiceElement> create(String pathPrefix, Object object, Method method,
                                                List<RequestConverterFunction> baseRequestConverters,
                                                List<ResponseConverterFunction> baseResponseConverters,
                                                List<ExceptionHandlerFunction> baseExceptionHandlers) {

        final Set<Annotation> methodAnnotations = httpMethodAnnotations(method);
        if (methodAnnotations.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }

        final Class<?> clazz = object.getClass();
        final Map<HttpMethod, List<String>> httpMethodPatternsMap = getHttpMethodPatternsMap(method,
                                                                                             methodAnnotations);
        final String computedPathPrefix = computePathPrefix(clazz, pathPrefix);
        final Set<MediaType> consumableMediaTypes = consumableMediaTypes(method, clazz);
        final Set<MediaType> producibleMediaTypes = producibleMediaTypes(method, clazz);

        final List<Route> routes = httpMethodPatternsMap.entrySet().stream().flatMap(
                pattern -> {
                    final HttpMethod httpMethod = pattern.getKey();
                    final List<String> pathMappings = pattern.getValue();
                    return pathMappings.stream().map(
                            pathMapping -> Route.builder()
                                                .path(computedPathPrefix, pathMapping)
                                                .methods(httpMethod)
                                                .consumes(consumableMediaTypes)
                                                .produces(producibleMediaTypes)
                                                .matchesParams(
                                                        predicates(method, clazz, MatchesParam.class,
                                                                   MatchesParam::value))
                                                .matchesHeaders(
                                                        predicates(method, clazz, MatchesHeader.class,
                                                                   MatchesHeader::value))
                                                .build());
                }).collect(toImmutableList());

        final List<RequestConverterFunction> req =
                getAnnotatedInstances(method, clazz, RequestConverter.class, RequestConverterFunction.class)
                        .addAll(baseRequestConverters).build();
        final List<ResponseConverterFunction> res =
                getAnnotatedInstances(method, clazz, ResponseConverter.class, ResponseConverterFunction.class)
                        .addAll(baseResponseConverters).build();
        final List<ExceptionHandlerFunction> eh =
                getAnnotatedInstances(method, clazz, ExceptionHandler.class, ExceptionHandlerFunction.class)
                        .addAll(baseExceptionHandlers).add(defaultExceptionHandler).build();

        final ResponseHeadersBuilder defaultHeaders = ResponseHeaders.builder(defaultResponseStatus(method));

        final HttpHeadersBuilder defaultTrailers = HttpHeaders.builder();
        final String classAlias = clazz.getName();
        final String methodAlias = String.format("%s.%s()", classAlias, method.getName());
        setAdditionalHeader(defaultHeaders, clazz, "header", classAlias, "class", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultHeaders, method, "header", methodAlias, "method", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultTrailers, clazz, "trailer", classAlias, "class",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);
        setAdditionalHeader(defaultTrailers, method, "trailer", methodAlias, "method",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);

        if (defaultHeaders.status().isContentAlwaysEmpty() && !defaultTrailers.isEmpty()) {
            logger.warn("A response with HTTP status code '{}' cannot have a content. " +
                        "Trailers defined at '{}' might be ignored if HTTP/1.1 is used.",
                        defaultHeaders.status().code(), methodAlias);
        }

        final ResponseHeaders responseHeaders = defaultHeaders.build();
        final HttpHeaders responseTrailers = defaultTrailers.build();

        final boolean useBlockingTaskExecutor = AnnotationUtil.findFirst(method, Blocking.class) != null;

        return routes.stream().map(route -> {
            final List<AnnotatedValueResolver> resolvers = getAnnotatedValueResolvers(req, route, method,
                                                                                      clazz);
            return new AnnotatedServiceElement(
                    route,
                    new AnnotatedService(object, method, resolvers, eh, res, route, responseHeaders,
                                         responseTrailers, useBlockingTaskExecutor),
                    decorator(method, clazz));
        }).collect(toImmutableList());
    }

    private static List<AnnotatedValueResolver> getAnnotatedValueResolvers(List<RequestConverterFunction> req,
                                                                           Route route, Method method,
                                                                           Class<?> clazz) {
        final Set<String> expectedParamNames = route.paramNames();
        List<AnnotatedValueResolver> resolvers;
        try {
            resolvers = AnnotatedValueResolver.ofServiceMethod(method, expectedParamNames,
                                                               AnnotatedValueResolver
                                                                       .toRequestObjectResolvers(req));
        } catch (NoParameterException ignored) {
            // Allow no parameter like below:
            //
            // @Get("/")
            // public String method1() { ... }
            //
            resolvers = ImmutableList.of();
        }

        final Set<String> requiredParamNames =
                resolvers.stream()
                         .filter(AnnotatedValueResolver::isPathVariable)
                         .map(AnnotatedValueResolver::httpElementName)
                         .collect(toImmutableSet());

        if (!expectedParamNames.containsAll(requiredParamNames)) {
            final Set<String> missing = Sets.difference(requiredParamNames, expectedParamNames);
            throw new IllegalArgumentException("cannot find path variables: " + missing);
        }

        // Warn unused path variables only if there's no '@RequestObject' annotation.
        if (resolvers.stream().noneMatch(r -> r.annotationType() == RequestObject.class) &&
            !requiredParamNames.containsAll(expectedParamNames)) {
            final Set<String> missing = Sets.difference(expectedParamNames, requiredParamNames);
            logger.warn("Some path variables of the method '" + method.getName() +
                        "' of the class '" + clazz.getName() +
                        "' do not have their corresponding parameters annotated with @Param. " +
                        "They would not be automatically injected: " + missing);
        }

        return resolvers;
    }

    /**
     * Returns the list of {@link Path} annotated methods.
     */
    private static List<Method> requestMappingMethods(Object object) {
        return getAllMethods(object.getClass(), withModifier(Modifier.PUBLIC))
                .stream()
                // Lookup super classes just in case if the object is a proxy.
                .filter(m -> AnnotationUtil.getAnnotations(m, FindOption.LOOKUP_SUPER_CLASSES)
                                           .stream()
                                           .map(Annotation::annotationType)
                                           .anyMatch(a -> a == Path.class ||
                                       HTTP_METHOD_MAP.containsKey(a)))
                .sorted(Comparator.comparingInt(AnnotatedServiceFactory::order))
                .collect(toImmutableList());
    }

    /**
     * Returns the value of the order of the {@link Method}. The order could be retrieved from {@link Order}
     * annotation. 0 would be returned if there is no specified {@link Order} annotation.
     */
    private static int order(Method method) {
        final Order order = AnnotationUtil.findFirst(method, Order.class);
        return order != null ? order.value() : 0;
    }

    /**
     * Returns {@link Set} of HTTP method annotations of a given method.
     * The annotations are as follows.
     *
     * @see Options
     * @see Get
     * @see Head
     * @see Post
     * @see Put
     * @see Patch
     * @see Delete
     * @see Trace
     */
    private static Set<Annotation> httpMethodAnnotations(Method method) {
        return AnnotationUtil.getAnnotations(method, FindOption.LOOKUP_SUPER_CLASSES)
                             .stream()
                             .filter(annotation -> HTTP_METHOD_MAP.containsKey(annotation.annotationType()))
                             .collect(Collectors.toSet());
    }

    /**
     * Returns the set of {@link MediaType}s specified by {@link Consumes} annotation.
     */
    private static Set<MediaType> consumableMediaTypes(Method method, Class<?> clazz) {
        List<Consumes> consumes = AnnotationUtil.findAll(method, Consumes.class);

        if (consumes.isEmpty()) {
            consumes = AnnotationUtil.findAll(clazz, Consumes.class);
        }

        final List<MediaType> types =
                consumes.stream()
                        .map(Consumes::value)
                        .map(MediaType::parse)
                        .collect(toImmutableList());
        return listToSet(types, Consumes.class);
    }

    /**
     * Returns the list of {@link MediaType}s specified by {@link Produces} annotation.
     */
    private static Set<MediaType> producibleMediaTypes(Method method, Class<?> clazz) {
        List<Produces> produces = AnnotationUtil.findAll(method, Produces.class);

        if (produces.isEmpty()) {
            produces = AnnotationUtil.findAll(clazz, Produces.class);
        }

        final List<MediaType> types =
                produces.stream()
                        .map(Produces::value)
                        .map(MediaType::parse)
                        .peek(type -> {
                            if (type.hasWildcard()) {
                                throw new IllegalArgumentException(
                                        "Producible media types must not have a wildcard: " + type);
                            }
                        })
                        .collect(toImmutableList());
        return listToSet(types, Produces.class);
    }

    /**
     * Returns a list of predicates which will be used to evaluate whether a request can be accepted
     * by a service method.
     */
    private static <T extends Annotation> List<String> predicates(Method method, Class<?> clazz,
                                                                  Class<T> annotationType,
                                                                  Function<T, String> toStringPredicate) {
        final List<T> classLevel = AnnotationUtil.findAll(clazz, annotationType);
        final List<T> methodLevel = AnnotationUtil.findAll(method, annotationType);
        return Streams.concat(classLevel.stream(), methodLevel.stream())
                      .map(toStringPredicate).collect(toImmutableList());
    }

    /**
     * Converts the list of {@link MediaType}s to a set. It raises an {@link IllegalArgumentException} if the
     * list has duplicate elements.
     */
    private static Set<MediaType> listToSet(List<MediaType> types, Class<?> annotationClass) {
        final Set<MediaType> set = new LinkedHashSet<>();
        for (final MediaType type : types) {
            if (!set.add(type)) {
                throw new IllegalArgumentException(
                        "Duplicated media type for @" + annotationClass.getSimpleName() + ": " + type);
            }
        }
        return ImmutableSet.copyOf(set);
    }

    /**
     * Returns path patterns for each {@link HttpMethod}. The path pattern might be specified by
     * {@link Path} or HTTP method annotations such as {@link Get} and {@link Post}. Path patterns
     * may be specified by either HTTP method annotations, or {@link Path} annotations but not both
     * simultaneously.
     */
    private static Map<HttpMethod, List<String>> getHttpMethodPatternsMap(Method method,
                                                                          Set<Annotation> methodAnnotations) {
        final List<String> pathPatterns = AnnotationUtil.findAll(method, Path.class).stream().map(Path::value)
                                                        .collect(toImmutableList());
        final boolean usePathPatterns = !pathPatterns.isEmpty();

        final Map<HttpMethod, List<String>> httpMethodAnnotatedPatternMap =
                getHttpMethodAnnotatedPatternMap(methodAnnotations);
        if (httpMethodAnnotatedPatternMap.isEmpty()) {
            throw new IllegalArgumentException(method.getDeclaringClass().getName() + '#' + method.getName() +
                                               " must have an HTTP method annotation.");
        }
        return httpMethodAnnotatedPatternMap.entrySet().stream().collect(
                ImmutableMap.toImmutableMap(
                        Entry::getKey,
                        entry -> {
                            final List<String> httpMethodPaths = entry.getValue();
                            if (usePathPatterns && !httpMethodPaths.isEmpty()) {
                                throw new IllegalArgumentException(
                                        method.getDeclaringClass().getName() + '#' + method.getName() +
                                        " cannot specify both an HTTP mapping and a Path mapping.");
                            }
                            if (usePathPatterns) {
                                httpMethodPaths.addAll(pathPatterns);
                            }
                            if (httpMethodPaths.isEmpty()) {
                                throw new IllegalArgumentException("A path pattern should be specified by" +
                                                                   " @Path or HTTP method annotations.");
                            }
                            return ImmutableList.copyOf(httpMethodPaths);
                        }));
    }

    private static Map<HttpMethod, List<String>> getHttpMethodAnnotatedPatternMap(
            Set<Annotation> methodAnnotations) {
        final Map<HttpMethod, List<String>> httpMethodPatternMap = new EnumMap<>(HttpMethod.class);
        methodAnnotations.stream()
                         .filter(annotation -> HTTP_METHOD_MAP.containsKey(annotation.annotationType()))
                         .forEach(annotation -> {
                             final HttpMethod httpMethod = HTTP_METHOD_MAP.get(annotation.annotationType());
                             final String value = (String) invokeValueMethod(annotation);
                             final List<String> patterns = httpMethodPatternMap
                                     .computeIfAbsent(httpMethod, ignored -> new ArrayList<>());
                             if (DefaultValues.isSpecified(value)) {
                                 patterns.add(value);
                             }
                         });
        return httpMethodPatternMap;
    }

    /**
     * Returns a decorator chain which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    private static Function<? super HttpService, ? extends HttpService> decorator(
            Method method, Class<?> clazz) {

        final List<DecoratorAndOrder> decorators = collectDecorators(clazz, method);

        Function<? super HttpService, ? extends HttpService> decorator = Function.identity();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            final DecoratorAndOrder d = decorators.get(i);
            decorator = decorator.andThen(d.decorator());
        }
        return decorator;
    }

    /**
     * Returns a decorator list which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    @VisibleForTesting
    static List<DecoratorAndOrder> collectDecorators(Class<?> clazz, Method method) {
        final List<DecoratorAndOrder> decorators = new ArrayList<>();

        // Class-level decorators are applied before method-level decorators.
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(clazz));
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(method));

        // Sort decorators by "order" attribute values.
        decorators.sort(Comparator.comparing(DecoratorAndOrder::order));

        return decorators;
    }

    /**
     * Adds decorators to the specified {@code list}. Decorators which are annotated with {@link Decorator}
     * and user-defined decorators will be collected.
     */
    private static void collectDecorators(List<DecoratorAndOrder> list, List<Annotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }

        // Respect the order of decorators which is specified by a user. The first one is first applied
        // for most of the cases. But if @Decorator and user-defined decorators are specified in a mixed order,
        // the specified order and the applied order can be different. To overcome this problem, we introduce
        // "order" attribute to @Decorator annotation to sort decorators. If a user-defined decorator
        // annotation has "order" attribute, it will be also used for sorting.
        for (final Annotation annotation : annotations) {
            if (annotation instanceof Decorator) {
                final Decorator d = (Decorator) annotation;
                list.add(new DecoratorAndOrder(d, newDecorator(d), d.order()));
                continue;
            }

            if (annotation instanceof Decorators) {
                final Decorator[] decorators = ((Decorators) annotation).value();
                for (final Decorator d : decorators) {
                    list.add(new DecoratorAndOrder(d, newDecorator(d), d.order()));
                }
                continue;
            }

            DecoratorAndOrder udd = userDefinedDecorator(annotation);
            if (udd != null) {
                list.add(udd);
                continue;
            }

            // If user-defined decorators are repeatable and they are specified more than once.
            try {
                final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                    withName("value")), null);
                assert method != null : "No 'value' method is found from " + annotation;
                final Annotation[] decorators = (Annotation[]) method.invoke(annotation);
                for (final Annotation decorator : decorators) {
                    udd = userDefinedDecorator(decorator);
                    if (udd == null) {
                        break;
                    }
                    list.add(udd);
                }
            } catch (Throwable ignore) {
                // The annotation may be a container of a decorator or may be not, so we just ignore
                // any exception from this clause.
            }
        }
    }

    /**
     * Returns a decorator with its order if the specified {@code annotation} is one of the user-defined
     * decorator annotation.
     */
    @Nullable
    private static DecoratorAndOrder userDefinedDecorator(Annotation annotation) {
        // User-defined decorator MUST be annotated with @DecoratorFactory annotation.
        final DecoratorFactory d = AnnotationUtil.findFirstDeclared(annotation.annotationType(),
                                                                    DecoratorFactory.class);
        if (d == null) {
            return null;
        }

        // In case of user-defined decorator, we need to create a new decorator from its factory.
        @SuppressWarnings("unchecked")
        final DecoratorFactoryFunction<Annotation> factory = getInstance(d, DecoratorFactoryFunction.class);

        // If the annotation has "order" attribute, we can use it when sorting decorators.
        int order = 0;
        try {
            final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                withName("order")), null);
            if (method != null) {
                final Object value = method.invoke(annotation);
                if (value instanceof Integer) {
                    order = (Integer) value;
                }
            }
        } catch (Throwable ignore) {
            // A user-defined decorator may not have an 'order' attribute.
            // If it does not exist, '0' is used by default.
        }
        return new DecoratorAndOrder(annotation, factory.newDecorator(annotation), order);
    }

    /**
     * Returns a new decorator which decorates an {@link HttpService} by the specified
     * {@link Decorator}.
     */
    private static Function<? super HttpService, ? extends HttpService> newDecorator(Decorator decorator) {
        return service -> service.decorate(getInstance(decorator, DecoratingHttpServiceFunction.class));
    }

    /**
     * Returns a {@link Builder} which has the instances specified by the annotations of the
     * {@code annotationType}. The annotations of the specified {@code method} and {@code clazz} will be
     * collected respectively.
     */
    private static <T extends Annotation, R> Builder<R> getAnnotatedInstances(
            AnnotatedElement method, AnnotatedElement clazz, Class<T> annotationType, Class<R> resultType) {
        final Builder<R> builder = new Builder<>();
        Stream.concat(AnnotationUtil.findAll(method, annotationType).stream(),
                      AnnotationUtil.findAll(clazz, annotationType).stream())
              .forEach(annotation -> builder.add(getInstance(annotation, resultType)));
        return builder;
    }

    /**
     * Returns a cached instance of the specified {@link Class} which is specified in the given
     * {@link Annotation}.
     */
    static <T> T getInstance(Annotation annotation, Class<T> expectedType) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> clazz = (Class<? extends T>) invokeValueMethod(annotation);
            return expectedType.cast(instanceCache.computeIfAbsent(clazz, type -> {
                try {
                    return getInstance0(clazz);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "A class specified in @" + annotation.getClass().getSimpleName() +
                            " annotation must have an accessible default constructor: " + clazz.getName(), e);
                }
            }));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                    "A class specified in @" + annotation.getClass().getSimpleName() +
                    " annotation cannot be cast to " + expectedType, e);
        }
    }

    /**
     * Returns a cached instance of the specified {@link Class}.
     */
    static <T> T getInstance(Class<T> clazz) {
        @SuppressWarnings("unchecked")
        final T casted = (T) instanceCache.computeIfAbsent(clazz, type -> {
            try {
                return getInstance0(clazz);
            } catch (Exception e) {
                throw new IllegalStateException("A class must have an accessible default constructor: " +
                                                clazz.getName(), e);
            }
        });
        return casted;
    }

    private static <T> T getInstance0(Class<? extends T> clazz) throws Exception {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);
        assert constructor != null : "No default constructor is found from " + clazz.getName();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * Returns an object which is returned by {@code value()} method of the specified annotation {@code a}.
     */
    private static Object invokeValueMethod(Annotation a) {
        try {
            final Method method = Iterables.getFirst(getMethods(a.getClass(), withName("value")), null);
            assert method != null : "No 'value' method is found from " + a;
            return method.invoke(a);
        } catch (Exception e) {
            throw new IllegalStateException("An annotation @" + a.getClass().getSimpleName() +
                                            " must have a 'value' method", e);
        }
    }

    /**
     * Returns the description of the specified {@link AnnotatedElement}.
     */
    @Nullable
    static String findDescription(AnnotatedElement annotatedElement) {
        requireNonNull(annotatedElement, "annotatedElement");
        final Description description = AnnotationUtil.findFirst(annotatedElement, Description.class);
        if (description != null) {
            final String value = description.value();
            if (DefaultValues.isSpecified(value)) {
                checkArgument(!value.isEmpty(), "value is empty");
                return value;
            }
        }
        return null;
    }

    /**
     * Returns the path prefix to use. If there is {@link PathPrefix} annotation on the class
     * then path prefix is computed by concatenating pathPrefix and value from annotation else
     * returns pathPrefix.
     */
    private static String computePathPrefix(Class<?> clazz, String pathPrefix) {
        ensureAbsolutePath(pathPrefix, "pathPrefix");
        final PathPrefix pathPrefixAnnotation = AnnotationUtil.findFirst(clazz, PathPrefix.class);
        if (pathPrefixAnnotation == null) {
            return pathPrefix;
        }
        final String pathPrefixFromAnnotation = pathPrefixAnnotation.value();
        ensureAbsolutePath(pathPrefixFromAnnotation, "pathPrefixFromAnnotation");
        return concatPaths(pathPrefix, pathPrefixFromAnnotation);
    }

    private AnnotatedServiceFactory() {}

    /**
     * An internal class to hold a decorator with its order.
     */
    @VisibleForTesting
    static final class DecoratorAndOrder {
        // Keep the specified annotation for testing purpose.
        private final Annotation annotation;
        private final Function<? super HttpService, ? extends HttpService> decorator;
        private final int order;

        private DecoratorAndOrder(Annotation annotation,
                                  Function<? super HttpService, ? extends HttpService> decorator,
                                  int order) {
            this.annotation = annotation;
            this.decorator = decorator;
            this.order = order;
        }

        Annotation annotation() {
            return annotation;
        }

        Function<? super HttpService, ? extends HttpService> decorator() {
            return decorator;
        }

        int order() {
            return order;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("annotation", annotation())
                              .add("decorator", decorator())
                              .add("order", order())
                              .toString();
        }
    }
}
