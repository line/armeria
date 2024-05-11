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
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.getAnnotatedInstances;
import static com.linecorp.armeria.internal.server.annotation.ClassUtil.typeToClass;
import static com.linecorp.armeria.internal.server.annotation.ClassUtil.unwrapAsyncType;
import static com.linecorp.armeria.internal.server.annotation.ProcessedDocumentationHelper.getFileName;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.withModifier;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.NoParameterException;
import com.linecorp.armeria.internal.server.annotation.AnnotationUtil.FindOption;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil.DecoratorAndOrder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.AdditionalTrailer;
import com.linecorp.armeria.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Decorator;
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
import com.linecorp.armeria.server.docs.DescriptionInfo;

/**
 * Builds a list of {@link AnnotatedService}s from an {@link Object}.
 * This class is not supposed to be used by a user. Please check out the documentation
 * <a href="https://armeria.dev/docs/server-annotated-service">Annotated HTTP Service</a>
 * to use {@link AnnotatedService}.
 */
public final class AnnotatedServiceFactory {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedServiceFactory.class);

    private static final Cache<String, Properties> DOCUMENTATION_PROPERTIES_CACHE =
            CacheBuilder.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build();

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
            String pathPrefix, Object object, boolean useBlockingTaskExecutor,
            List<RequestConverterFunction> requestConverterFunctions,
            List<ResponseConverterFunction> responseConverterFunctions,
            List<ExceptionHandlerFunction> exceptionHandlerFunctions,
            DependencyInjector dependencyInjector, @Nullable String queryDelimiter) {
        final List<Method> methods = requestMappingMethods(object);
        final Builder<AnnotatedServiceElement> builder = ImmutableList.builder();

        final Map<String, Integer> overloadIds = new HashMap<>();
        // Can't sort methods to find the overloaded methods because methods are ordered using @Order.
        for (Method method : methods) {
            final String methodName = method.getName();
            final int overloadId;
            if (overloadIds.containsKey(methodName)) {
                overloadId = overloadIds.get(methodName) + 1;
            } else {
                overloadId = 0;
            }
            overloadIds.put(methodName, overloadId);
            builder.addAll(create(pathPrefix, object, method, overloadId, useBlockingTaskExecutor,
                                  requestConverterFunctions, responseConverterFunctions,
                                  exceptionHandlerFunctions, dependencyInjector, queryDelimiter));
        }
        return builder.build();
    }

    private static HttpStatus defaultResponseStatus(Method method, Class<?> clazz) {
        final StatusCode statusCodeAnnotation = AnnotationUtil.findFirst(method, StatusCode.class);
        if (statusCodeAnnotation != null) {
            return HttpStatus.valueOf(statusCodeAnnotation.value());
        }

        // Set a default HTTP status code for a response depending on the return type of the method.
        final Class<?> returnType = typeToClass(unwrapAsyncType(method.getGenericReturnType()));

        final boolean isVoidReturnType = returnType == Void.class ||
                                         returnType == void.class ||
                                         KotlinUtil.isSuspendingAndReturnTypeUnit(method);

        if (isVoidReturnType) {
            final List<Produces> producesAnnotations = AnnotationUtil.findAll(method, Produces.class);
            if (!producesAnnotations.isEmpty()) {
                logger.warn("The following @Produces annotations '{}' for '{}.{}' will be ignored " +
                            "because the return type is void.",
                            producesAnnotations, clazz.getSimpleName(), method.getName());
            }
        }

        return isVoidReturnType ? HttpStatus.NO_CONTENT : HttpStatus.OK;
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
                                                int overloadId, boolean useBlockingTaskExecutor,
                                                List<RequestConverterFunction> baseRequestConverters,
                                                List<ResponseConverterFunction> baseResponseConverters,
                                                List<ExceptionHandlerFunction> baseExceptionHandlers,
                                                DependencyInjector dependencyInjector,
                                                @Nullable String queryDelimiter) {
        if (KotlinUtil.getCallKotlinSuspendingMethod() == null && KotlinUtil.maybeSuspendingFunction(method)) {
            throw new IllegalArgumentException(
                    "Kotlin suspending functions are supported " +
                    "only when you added 'armeria-kotlin' as a dependency.\n" +
                    "See https://armeria.dev/docs/server-annotated-service#kotlin-coroutines-support " +
                    "for more information.");
        }

        final Class<?> clazz = object.getClass();
        final List<Route> routes = routes(method, clazz, pathPrefix);

        final List<RequestConverterFunction> req =
                getAnnotatedInstances(method, clazz, RequestConverter.class,
                                      RequestConverterFunction.class, dependencyInjector)
                        .addAll(baseRequestConverters).build();
        final List<ResponseConverterFunction> res =
                getAnnotatedInstances(method, clazz, ResponseConverter.class,
                                      ResponseConverterFunction.class, dependencyInjector)
                        .addAll(baseResponseConverters).build();
        final List<ExceptionHandlerFunction> eh =
                getAnnotatedInstances(method, clazz, ExceptionHandler.class,
                                      ExceptionHandlerFunction.class, dependencyInjector)
                        .addAll(baseExceptionHandlers).build();

        final String classAlias = clazz.getName();
        final String methodAlias = String.format("%s.%s()", classAlias, method.getName());
        final HttpHeaders responseHeaders = responseHeaders(method, clazz, classAlias, methodAlias);
        final HttpHeaders responseTrailers = responseTrailers(method, clazz, classAlias, methodAlias);

        final HttpStatus defaultStatus = defaultResponseStatus(method, clazz);
        if (defaultStatus.isContentAlwaysEmpty() && !responseTrailers.isEmpty()) {
            logger.warn("A response with HTTP status code '{}' cannot have a content. " +
                        "Trailers defined at '{}' might be ignored if HTTP/1.1 is used.",
                        defaultStatus.code(), methodAlias);
        }

        final boolean needToUseBlockingTaskExecutor =
                needToUseBlockingTaskExecutor(object, method, useBlockingTaskExecutor);

        return routes.stream().map(route -> {
            final List<AnnotatedValueResolver> resolvers =
                    getAnnotatedValueResolvers(req, route, method, clazz,
                                               needToUseBlockingTaskExecutor, dependencyInjector,
                                               queryDelimiter);
            return new AnnotatedServiceElement(
                    route,
                    new DefaultAnnotatedService(object, method, overloadId,
                                                resolvers, eh, res, route, defaultStatus,
                                                responseHeaders, responseTrailers,
                                                needToUseBlockingTaskExecutor),
                    decorator(method, clazz, dependencyInjector));
        }).collect(toImmutableList());
    }

    private static List<Route> routes(Method method, Class<?> clazz, String pathPrefix) {
        final Set<Annotation> methodAnnotations = httpMethodAnnotations(method);
        if (methodAnnotations.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }
        final Map<HttpMethod, List<String>> httpMethodPatternsMap =
                getHttpMethodPatternsMap(method, methodAnnotations);
        final String computedPathPrefix = computePathPrefix(clazz, pathPrefix);
        final Set<MediaType> consumableMediaTypes = consumableMediaTypes(method, clazz);
        final Set<MediaType> producibleMediaTypes = producibleMediaTypes(method, clazz);
        return httpMethodPatternsMap.entrySet().stream().flatMap(
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
    }

    private static HttpHeaders responseHeaders(Method method, Class<?> clazz, String classAlias,
                                               String methodAlias) {
        final HttpHeadersBuilder defaultHeaders = HttpHeaders.builder();
        setAdditionalHeader(defaultHeaders, clazz, "header", classAlias, "class", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultHeaders, method, "header", methodAlias, "method", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        return defaultHeaders.build();
    }

    private static HttpHeaders responseTrailers(Method method, Class<?> clazz, String classAlias,
                                                String methodAlias) {
        final HttpHeadersBuilder defaultTrailers = HttpHeaders.builder();
        setAdditionalHeader(defaultTrailers, clazz, "trailer", classAlias, "class",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);
        setAdditionalHeader(defaultTrailers, method, "trailer", methodAlias, "method",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);
        return defaultTrailers.build();
    }

    private static boolean needToUseBlockingTaskExecutor(Object object, Method method,
                                                         boolean useBlockingTaskExecutor) {
        return useBlockingTaskExecutor ||
               AnnotationUtil.findFirst(method, Blocking.class) != null ||
               AnnotationUtil.findFirst(object.getClass(), Blocking.class) != null;
    }

    private static List<AnnotatedValueResolver> getAnnotatedValueResolvers(
            List<RequestConverterFunction> req,
            Route route, Method method,
            Class<?> clazz,
            boolean useBlockingExecutor,
            DependencyInjector dependencyInjector,
            @Nullable String queryDelimiter) {
        final Set<String> expectedParamNames = route.paramNames();
        List<AnnotatedValueResolver> resolvers;
        try {
            resolvers = AnnotatedValueResolver.ofServiceMethod(
                    method, expectedParamNames,
                    AnnotatedValueResolver.toRequestObjectResolvers(req, method),
                    useBlockingExecutor, dependencyInjector, queryDelimiter);
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
                            } else if (httpMethodPaths.isEmpty()) {
                                // Add an empty value if HTTP method annotation value is empty or not specified.
                                httpMethodPaths.add("");
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
                             final String value = (String) AnnotatedObjectFactory.invokeValueMethod(annotation);
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
            Method method, Class<?> clazz, DependencyInjector dependencyInjector) {
        final List<DecoratorAndOrder> decorators = DecoratorAnnotationUtil.collectDecorators(clazz, method);
        Function<? super HttpService, ? extends HttpService> decorator = Function.identity();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            final DecoratorAndOrder d = decorators.get(i);
            decorator = decorator.andThen(d.decorator(dependencyInjector));
        }
        return decorator;
    }

    /**
     * Returns the description of the specified {@link AnnotatedElement}.
     */
    static DescriptionInfo findDescription(AnnotatedElement annotatedElement) {
        requireNonNull(annotatedElement, "annotatedElement");
        final Description description = AnnotationUtil.findFirstDescription(annotatedElement);
        if (description != null) {
            final String value = description.value();
            if (DefaultValues.isSpecified(value)) {
                checkArgument(!value.isEmpty(), "value is empty.");
                return DescriptionInfo.from(description);
            }
        } else if (annotatedElement instanceof Parameter) {
            // JavaDoc/KDoc descriptions only exist for method parameters
            final Parameter parameter = (Parameter) annotatedElement;
            final Executable executable = parameter.getDeclaringExecutable();
            final Class<?> clazz = executable.getDeclaringClass();
            final String fileName = getFileName(clazz.getCanonicalName());
            final String propertyName = executable.getName() + '.' + parameter.getName();
            final Properties cachedProperties = DOCUMENTATION_PROPERTIES_CACHE.getIfPresent(fileName);
            if (cachedProperties != null) {
                final String propertyValue = cachedProperties.getProperty(propertyName);
                return propertyValue != null ? DescriptionInfo.of(propertyValue) : DescriptionInfo.empty();
            }
            try (InputStream stream = AnnotatedServiceFactory.class.getClassLoader()
                                                                   .getResourceAsStream(fileName)) {
                if (stream == null) {
                    return DescriptionInfo.empty();
                }
                final Properties properties = new Properties();
                properties.load(stream);
                DOCUMENTATION_PROPERTIES_CACHE.put(fileName, properties);

                final String propertyValue = properties.getProperty(propertyName);
                return propertyValue != null ? DescriptionInfo.of(propertyValue) : DescriptionInfo.empty();
            } catch (IOException exception) {
                logger.warn("Failed to load an API description file: {}", fileName, exception);
            }
        }
        return DescriptionInfo.empty();
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
}
