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

package com.linecorp.armeria.internal.annotation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.toImmutableEnumSet;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.internal.PathMappingUtil.EXACT;
import static com.linecorp.armeria.internal.PathMappingUtil.GLOB;
import static com.linecorp.armeria.internal.PathMappingUtil.PREFIX;
import static com.linecorp.armeria.internal.PathMappingUtil.REGEX;
import static com.linecorp.armeria.internal.PathMappingUtil.ensureAbsolutePath;
import static com.linecorp.armeria.internal.PathMappingUtil.newLoggerName;
import static com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.toRequestObjectResolvers;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findAll;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findFirst;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findFirstDeclared;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.getAllAnnotations;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.getAnnotations;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.NoParameterException;
import com.linecorp.armeria.internal.annotation.AnnotationUtil.FindOption;
import com.linecorp.armeria.server.AbstractPathMapping;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.PathMappingContext;
import com.linecorp.armeria.server.PathMappingResult;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.AdditionalTrailer;
import com.linecorp.armeria.server.annotation.ConsumeType;
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
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProduceType;
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
 * Builds a list of {@link AnnotatedHttpService}s from an {@link Object}.
 * This class is not supposed to be used by a user. Please check out the documentation
 * <a href="https://line.github.io/armeria/server-annotated-service.html#annotated-http-service">
 * Annotated HTTP Service</a> to use {@link AnnotatedHttpService}.
 */
public final class AnnotatedHttpServiceFactory {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServiceFactory.class);

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
     * Returns the list of {@link AnnotatedHttpService} defined by {@link Path} and HTTP method annotations
     * from the specified {@code object}.
     */
    public static List<AnnotatedHttpServiceElement> find(String pathPrefix, Object object,
                                                         Iterable<?> exceptionHandlersAndConverters) {
        Builder<ExceptionHandlerFunction> exceptionHandlers = null;
        Builder<RequestConverterFunction> requestConverters = null;
        Builder<ResponseConverterFunction> responseConverters = null;

        for (final Object o : exceptionHandlersAndConverters) {
            boolean added = false;
            if (o instanceof ExceptionHandlerFunction) {
                if (exceptionHandlers == null) {
                    exceptionHandlers = ImmutableList.builder();
                }
                exceptionHandlers.add((ExceptionHandlerFunction) o);
                added = true;
            }
            if (o instanceof RequestConverterFunction) {
                if (requestConverters == null) {
                    requestConverters = ImmutableList.builder();
                }
                requestConverters.add((RequestConverterFunction) o);
                added = true;
            }
            if (o instanceof ResponseConverterFunction) {
                if (responseConverters == null) {
                    responseConverters = ImmutableList.builder();
                }
                responseConverters.add((ResponseConverterFunction) o);
                added = true;
            }
            if (!added) {
                throw new IllegalArgumentException(o.getClass().getName() +
                                                   " is neither an exception handler nor a converter.");
            }
        }

        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlers != null ? exceptionHandlers.build() : ImmutableList.of();
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverters != null ? requestConverters.build() : ImmutableList.of();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverters != null ? responseConverters.build() : ImmutableList.of();

        final List<Method> methods = requestMappingMethods(object);
        return methods.stream()
                      .map((Method method) -> create(pathPrefix, object, method, exceptionHandlerFunctions,
                                                     requestConverterFunctions, responseConverterFunctions))
                      .collect(toImmutableList());
    }

    private static HttpStatus defaultResponseStatus(Optional<HttpStatus> defaultResponseStatus,
                                                    Method method) {
        return defaultResponseStatus.orElseGet(() -> {
            // Set a default HTTP status code for a response depending on the return type of the method.
            final Class<?> returnType = method.getReturnType();
            return returnType == Void.class ||
                   returnType == void.class ? HttpStatus.NO_CONTENT : HttpStatus.OK;
        });
    }

    private static <T extends Annotation> void setAdditionalHeader(HttpHeaders headers,
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
        findAll(element, annotation).forEach(header -> {
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
     * Returns an {@link AnnotatedHttpService} instance defined to {@code method} of {@code object} using
     * {@link Path} annotation.
     */
    private static AnnotatedHttpServiceElement create(String pathPrefix, Object object, Method method,
                                                      List<ExceptionHandlerFunction> baseExceptionHandlers,
                                                      List<RequestConverterFunction> baseRequestConverters,
                                                      List<ResponseConverterFunction> baseResponseConverters) {

        final Set<Annotation> methodAnnotations = httpMethodAnnotations(method);
        if (methodAnnotations.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }

        final Set<HttpMethod> methods = toHttpMethods(methodAnnotations);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(method.getDeclaringClass().getName() + '#' + method.getName() +
                                               " must have an HTTP method annotation.");
        }

        final Class<?> clazz = object.getClass();
        final PathMapping pathMapping = pathStringMapping(pathPrefix, method, methodAnnotations)
                .withHttpHeaderInfo(methods, consumableMediaTypes(method, clazz),
                                    producibleMediaTypes(method, clazz));

        final List<ExceptionHandlerFunction> eh =
                getAnnotatedInstances(method, clazz, ExceptionHandler.class, ExceptionHandlerFunction.class)
                        .addAll(baseExceptionHandlers).add(defaultExceptionHandler).build();
        final List<RequestConverterFunction> req =
                getAnnotatedInstances(method, clazz, RequestConverter.class, RequestConverterFunction.class)
                        .addAll(baseRequestConverters).build();
        final List<ResponseConverterFunction> res =
                getAnnotatedInstances(method, clazz, ResponseConverter.class, ResponseConverterFunction.class)
                        .addAll(baseResponseConverters).build();

        List<AnnotatedValueResolver> resolvers;
        try {
            resolvers = AnnotatedValueResolver.ofServiceMethod(method, pathMapping.paramNames(),
                                                               toRequestObjectResolvers(req));
        } catch (NoParameterException ignored) {
            // Allow no parameter like below:
            //
            // @Get("/")
            // public String method1() { ... }
            //
            resolvers = ImmutableList.of();
        }

        final Set<String> expectedParamNames = pathMapping.paramNames();
        final Set<String> requiredParamNames =
                resolvers.stream()
                         .filter(AnnotatedValueResolver::isPathVariable)
                         .map(AnnotatedValueResolver::httpElementName)
                         .collect(Collectors.toSet());

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

        final Optional<HttpStatus> defaultResponseStatus = findFirst(method, StatusCode.class)
                .map(code -> {
                    final int statusCode = code.value();
                    checkArgument(statusCode >= 0,
                                  "invalid HTTP status code: %s (expected: >= 0)", statusCode);
                    return HttpStatus.valueOf(statusCode);
                });
        final HttpHeaders defaultHeaders = HttpHeaders.of(defaultResponseStatus(defaultResponseStatus, method));

        final HttpHeaders defaultTrailingHeaders = HttpHeaders.of();
        final String classAlias = clazz.getName();
        final String methodAlias = String.format("%s.%s()", classAlias, method.getName());
        setAdditionalHeader(defaultHeaders, clazz, "header", classAlias, "class", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultHeaders, method, "header", methodAlias, "method", AdditionalHeader.class,
                            AdditionalHeader::name, AdditionalHeader::value);
        setAdditionalHeader(defaultTrailingHeaders, clazz, "trailer", classAlias, "class",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);
        setAdditionalHeader(defaultTrailingHeaders, method, "trailer", methodAlias, "method",
                            AdditionalTrailer.class, AdditionalTrailer::name, AdditionalTrailer::value);

        if (ArmeriaHttpUtil.isContentAlwaysEmpty(defaultHeaders.status()) &&
            !defaultTrailingHeaders.isEmpty()) {
            logger.warn("A response with HTTP status code '{}' cannot have a content. " +
                        "Trailing headers defined at '{}' might be ignored.",
                        defaultHeaders.status().code(), methodAlias);
        }

        // A CORS preflight request can be received because we handle it specially. The following
        // decorator will prevent the service from an unexpected request which has OPTIONS method.
        final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> initialDecorator;
        if (methods.contains(HttpMethod.OPTIONS)) {
            initialDecorator = Function.identity();
        } else {
            initialDecorator = delegate -> new SimpleDecoratingService<HttpRequest, HttpResponse>(delegate) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    if (req.method() == HttpMethod.OPTIONS) {
                        // This must be a CORS preflight request.
                        throw HttpStatusException.of(HttpStatus.FORBIDDEN);
                    }
                    return delegate().serve(ctx, req);
                }
            };
        }
        return new AnnotatedHttpServiceElement(pathMapping,
                                               new AnnotatedHttpService(object, method, resolvers, eh,
                                                                        res, pathMapping, defaultHeaders,
                                                                        defaultTrailingHeaders),
                                               decorator(method, clazz, initialDecorator));
    }

    /**
     * Returns the list of {@link Path} annotated methods.
     */
    private static List<Method> requestMappingMethods(Object object) {
        return getAllMethods(object.getClass(), withModifier(Modifier.PUBLIC))
                .stream()
                // Lookup super classes just in case if the object is a proxy.
                .filter(m -> getAnnotations(m, FindOption.LOOKUP_SUPER_CLASSES)
                        .stream()
                        .map(Annotation::annotationType)
                        .anyMatch(a -> a == Path.class ||
                                       HTTP_METHOD_MAP.containsKey(a)))
                .sorted(Comparator.comparingInt(AnnotatedHttpServiceFactory::order))
                .collect(toImmutableList());
    }

    /**
     * Returns the value of the order of the {@link Method}. The order could be retrieved from {@link Order}
     * annotation. 0 would be returned if there is no specified {@link Order} annotation.
     */
    private static int order(Method method) {
        final Order order = findFirst(method, Order.class).orElse(null);
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
        return getAnnotations(method, FindOption.LOOKUP_SUPER_CLASSES)
                .stream()
                .filter(annotation -> HTTP_METHOD_MAP.containsKey(annotation.annotationType()))
                .collect(Collectors.toSet());
    }

    /**
     * Returns {@link Set} of {@link HttpMethod}s mapped to HTTP method annotations.
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
    private static Set<HttpMethod> toHttpMethods(Set<Annotation> annotations) {
        return annotations.stream()
                          .map(annotation -> HTTP_METHOD_MAP.get(annotation.annotationType()))
                          .filter(Objects::nonNull)
                          .collect(toImmutableEnumSet());
    }

    /**
     * Returns the list of {@link MediaType}s specified by {@link Consumes} annotation.
     */
    private static List<MediaType> consumableMediaTypes(Method method, Class<?> clazz) {
        List<Consumes> consumes = findAll(method, Consumes.class);
        List<ConsumeType> consumeTypes = findAll(method, ConsumeType.class);

        if (consumes.isEmpty() && consumeTypes.isEmpty()) {
            consumes = findAll(clazz, Consumes.class);
            consumeTypes = findAll(clazz, ConsumeType.class);
        }

        final List<MediaType> types =
                Stream.concat(consumes.stream().map(Consumes::value),
                              consumeTypes.stream().map(ConsumeType::value))
                      .map(MediaType::parse)
                      .collect(toImmutableList());
        return ensureUniqueTypes(types, Consumes.class);
    }

    /**
     * Returns the list of {@link MediaType}s specified by {@link Produces} annotation.
     */
    private static List<MediaType> producibleMediaTypes(Method method, Class<?> clazz) {
        List<Produces> produces = findAll(method, Produces.class);
        List<ProduceType> produceTypes = findAll(method, ProduceType.class);

        if (produces.isEmpty() && produceTypes.isEmpty()) {
            produces = findAll(clazz, Produces.class);
            produceTypes = findAll(clazz, ProduceType.class);
        }

        final List<MediaType> types =
                Stream.concat(produces.stream().map(Produces::value),
                              produceTypes.stream().map(ProduceType::value))
                      .map(MediaType::parse)
                      .peek(type -> {
                          if (type.hasWildcard()) {
                              throw new IllegalArgumentException(
                                      "Producible media types must not have a wildcard: " + type);
                          }
                      })
                      .collect(toImmutableList());
        return ensureUniqueTypes(types, Produces.class);
    }

    private static List<MediaType> ensureUniqueTypes(List<MediaType> types, Class<?> annotationClass) {
        final Set<MediaType> set = new HashSet<>();
        for (final MediaType type : types) {
            if (!set.add(type)) {
                throw new IllegalArgumentException(
                        "Duplicated media type for @" + annotationClass.getSimpleName() + ": " + type);
            }
        }
        return types;
    }

    /**
     * Returns the {@link PathMapping} instance mapped to {@code method}.
     */
    private static PathMapping pathStringMapping(String pathPrefix, Method method,
                                                 Set<Annotation> methodAnnotations) {
        pathPrefix = ensureAbsolutePath(pathPrefix, "pathPrefix");
        if (!pathPrefix.endsWith("/")) {
            pathPrefix += '/';
        }

        final String pattern = findPattern(method, methodAnnotations);
        final PathMapping mapping = PathMapping.of(pattern);
        if ("/".equals(pathPrefix)) {
            // pathPrefix is not specified or "/".
            return mapping;
        }

        if (pattern.startsWith(EXACT)) {
            return PathMapping.ofExact(concatPaths(
                    pathPrefix, pattern.substring(EXACT.length())));
        }

        if (pattern.startsWith(PREFIX)) {
            return PathMapping.ofPrefix(concatPaths(
                    pathPrefix, pattern.substring(PREFIX.length())));
        }

        if (pattern.startsWith(GLOB)) {
            final String glob = pattern.substring(GLOB.length());
            if (glob.startsWith("/")) {
                return PathMapping.ofGlob(concatPaths(pathPrefix, glob));
            } else {
                // NB: We cannot use PathMapping.ofGlob(pathPrefix + "/**/" + glob) here
                //     because that will extract '/**/' as a path parameter, which a user never specified.
                return new PrefixAddingPathMapping(pathPrefix, mapping);
            }
        }

        if (pattern.startsWith(REGEX)) {
            return new PrefixAddingPathMapping(pathPrefix, mapping);
        }

        if (pattern.startsWith("/")) {
            // Default pattern
            return PathMapping.of(concatPaths(pathPrefix, pattern));
        }

        // Should never reach here because we validated the path pattern.
        throw new Error();
    }

    /**
     * Returns a specified path pattern. The path pattern might be specified by {@link Path} or
     * HTTP method annotations such as {@link Get} and {@link Post}.
     */
    private static String findPattern(Method method, Set<Annotation> methodAnnotations) {
        String pattern = findFirst(method, Path.class).map(Path::value)
                                                      .orElse(null);
        for (Annotation a : methodAnnotations) {
            final String p = (String) invokeValueMethod(a);
            if (DefaultValues.isUnspecified(p)) {
                continue;
            }
            checkArgument(pattern == null,
                          "Only one path can be specified. (" + pattern + ", " + p + ')');
            pattern = p;
        }
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException(
                    "A path pattern should be specified by @Path or HTTP method annotations.");
        }
        return pattern;
    }

    /**
     * Returns a decorator chain which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator(
            Method method, Class<?> clazz,
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> initialDecorator) {

        final List<DecoratorAndOrder> decorators = collectDecorators(clazz, method);

        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator = initialDecorator;
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
        collectDecorators(decorators, getAllAnnotations(clazz));
        collectDecorators(decorators, getAllAnnotations(method));

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
        final DecoratorFactory d =
                findFirstDeclared(annotation.annotationType(), DecoratorFactory.class).orElse(null);
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
     * Returns a new decorator which decorates a {@link Service} by the specified
     * {@link Decorator}.
     */
    @SuppressWarnings("unchecked")
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(Decorator decorator) {
        return service -> service.decorate(getInstance(decorator, DecoratingServiceFunction.class));
    }

    /**
     * Returns a {@link Builder} which has the instances specified by the annotations of the
     * {@code annotationType}. The annotations of the specified {@code method} and {@code clazz} will be
     * collected respectively.
     */
    private static <T extends Annotation, R> Builder<R> getAnnotatedInstances(
            AnnotatedElement method, AnnotatedElement clazz, Class<T> annotationType, Class<R> resultType) {
        final Builder<R> builder = new Builder<>();
        Stream.concat(findAll(method, annotationType).stream(),
                      findAll(clazz, annotationType).stream())
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
        final Optional<Description> description = findFirst(annotatedElement, Description.class);
        if (description.isPresent()) {
            final String value = description.get().value();
            if (DefaultValues.isSpecified(value)) {
                checkArgument(!value.isEmpty(), "value is empty");
                return value;
            }
        }
        return null;
    }

    private AnnotatedHttpServiceFactory() {}

    /**
     * A {@link PathMapping} implementation that combines path prefix and another {@link PathMapping}.
     */
    @VisibleForTesting
    static final class PrefixAddingPathMapping extends AbstractPathMapping {

        private final String pathPrefix;
        private final PathMapping mapping;
        private final String loggerName;
        private final String meterTag;

        PrefixAddingPathMapping(String pathPrefix, PathMapping mapping) {
            requireNonNull(mapping, "mapping");
            // mapping should be GlobPathMapping or RegexPathMapping
            assert mapping.regex().isPresent() : "unexpected mapping type: " + mapping.getClass().getName();
            this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
            this.mapping = mapping;
            loggerName = newLoggerName(pathPrefix) + '.' + mapping.loggerName();
            meterTag = PREFIX + pathPrefix + ',' + mapping.meterTag();
        }

        @Override
        protected PathMappingResult doApply(PathMappingContext mappingCtx) {
            final String path = mappingCtx.path();
            if (!path.startsWith(pathPrefix)) {
                return PathMappingResult.empty();
            }

            final PathMappingResult result =
                    mapping.apply(mappingCtx.overridePath(path.substring(pathPrefix.length() - 1)));
            if (result.isPresent()) {
                return PathMappingResult.of(path, mappingCtx.query(), result.pathParams());
            } else {
                return PathMappingResult.empty();
            }
        }

        @Override
        public Set<String> paramNames() {
            return mapping.paramNames();
        }

        @Override
        public String loggerName() {
            return loggerName;
        }

        @Override
        public String meterTag() {
            return meterTag;
        }

        @Override
        public Optional<String> prefix() {
            return Optional.of(pathPrefix);
        }

        @Override
        public Optional<String> regex() {
            return mapping.regex();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PrefixAddingPathMapping)) {
                return false;
            }

            final PrefixAddingPathMapping that = (PrefixAddingPathMapping) o;
            return pathPrefix.equals(that.pathPrefix) && mapping.equals(that.mapping);
        }

        @Override
        public int hashCode() {
            return 31 * pathPrefix.hashCode() + mapping.hashCode();
        }

        @Override
        public String toString() {
            return '[' + PREFIX + pathPrefix + ", " + mapping + ']';
        }
    }

    /**
     * An internal class to hold a decorator with its order.
     */
    @VisibleForTesting
    static final class DecoratorAndOrder {
        // Keep the specified annotation for testing purpose.
        private final Annotation annotation;
        private final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator;
        private final int order;

        private DecoratorAndOrder(Annotation annotation,
                                  Function<Service<HttpRequest, HttpResponse>,
                                          ? extends Service<HttpRequest, HttpResponse>> decorator,
                                  int order) {
            this.annotation = annotation;
            this.decorator = decorator;
            this.order = order;
        }

        Annotation annotation() {
            return annotation;
        }

        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator() {
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
