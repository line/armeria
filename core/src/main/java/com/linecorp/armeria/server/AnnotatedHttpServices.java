/*
 *  Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.toImmutableEnumSet;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.server.AbstractPathMapping.ensureAbsolutePath;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.server.annotation.BeanRequestConverterFunction;
import com.linecorp.armeria.server.annotation.ByteArrayRequestConverterFunction;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.ConsumeTypes;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProduceType;
import com.linecorp.armeria.server.annotation.ProduceTypes;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StringRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Trace;

/**
 * Builds a list of {@link AnnotatedHttpService}s from a Java object.
 */
final class AnnotatedHttpServices {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServices.class);

    /**
     * An instance map for reusing converters, exception handlers and decorators.
     */
    private static final ConcurrentMap<Class<?>, Object> instanceCache = new ConcurrentHashMap<>();

    /**
     * A default {@link ExceptionHandlerFunction} list.
     */
    private static final List<ExceptionHandlerFunction> defaultExceptionHandlers =
            ImmutableList.of(new DefaultExceptionHandler());

    /**
     * A default {@link RequestConverterFunction} list.
     */
    private static final List<RequestConverterFunction> defaultRequestConverters =
            ImmutableList.of(new BeanRequestConverterFunction(),
                             new JacksonRequestConverterFunction(),
                             new StringRequestConverterFunction(),
                             new ByteArrayRequestConverterFunction());

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
     * Returns the list of {@link Path} annotated methods.
     */
    private static List<Method> requestMappingMethods(Object object) {
        return Arrays.stream(object.getClass().getMethods())
                     .filter(m -> m.getAnnotation(Path.class) != null ||
                                  !httpMethodAnnotations(m).isEmpty())
                     .sorted(Comparator.comparingInt(AnnotatedHttpServices::order))
                     .collect(toImmutableList());
    }

    /**
     * Returns the value of the order of the {@link Method}. The order could be retrieved from {@link Order}
     * annotation. 0 would be returned if there is no specified {@link Order} annotation.
     */
    private static int order(Method method) {
        final Order order = method.getAnnotation(Order.class);
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
        return Arrays.stream(method.getAnnotations())
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
     * Returns the list of {@link MediaType}s specified by {@link ConsumeType} annotation.
     */
    private static List<MediaType> consumeTypes(Method method, Class<?> clazz) {
        final ConsumeType[] consumeTypes =
                method.isAnnotationPresent(ConsumeType.class) ||
                method.isAnnotationPresent(ConsumeTypes.class) ? method.getAnnotationsByType(ConsumeType.class)
                                                               : clazz.getAnnotationsByType(ConsumeType.class);
        if (consumeTypes == null || consumeTypes.length == 0) {
            return ImmutableList.of();
        }

        final List<MediaType> mediaTypes = new ArrayList<>();
        Arrays.stream(consumeTypes).forEach(e -> mediaTypes.add(MediaType.parse(e.value())));
        return mediaTypes;
    }

    /**
     * Returns the list of {@link MediaType}s specified by {@link ProduceType} annotation.
     */
    private static List<MediaType> produceTypes(Method method, Class<?> clazz) {
        final ProduceType[] produceTypes =
                method.isAnnotationPresent(ProduceType.class) ||
                method.isAnnotationPresent(ProduceTypes.class) ? method.getAnnotationsByType(ProduceType.class)
                                                               : clazz.getAnnotationsByType(ProduceType.class);
        if (produceTypes == null || produceTypes.length == 0) {
            return ImmutableList.of();
        }

        final List<MediaType> mediaTypes = new ArrayList<>();
        Arrays.stream(produceTypes).forEach(e -> {
            final MediaType type = MediaType.parse(e.value());
            if (type.hasWildcard()) {
                throw new IllegalArgumentException("@ProduceType must not have a wildcard: " + e.value());
            }
            mediaTypes.add(type);
        });
        return mediaTypes;
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

        if (pattern.startsWith(ExactPathMapping.PREFIX)) {
            return PathMapping.ofExact(concatPaths(
                    pathPrefix, pattern.substring(ExactPathMapping.PREFIX_LEN)));
        }

        if (pattern.startsWith(PrefixPathMapping.PREFIX)) {
            return PathMapping.ofPrefix(concatPaths(
                    pathPrefix, pattern.substring(PrefixPathMapping.PREFIX_LEN)));
        }

        if (pattern.startsWith(GlobPathMapping.PREFIX)) {
            final String glob = pattern.substring(GlobPathMapping.PREFIX_LEN);
            if (glob.startsWith("/")) {
                return PathMapping.ofGlob(concatPaths(pathPrefix, glob));
            } else {
                // NB: We cannot use PathMapping.ofGlob(pathPrefix + "/**/" + glob) here
                //     because that will extract '/**/' as a path parameter, which a user never specified.
                return new PrefixAddingPathMapping(pathPrefix, mapping);
            }
        }

        if (pattern.startsWith(RegexPathMapping.PREFIX)) {
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
        String pattern = null;

        final Path path = method.getAnnotation(Path.class);
        if (path != null) {
            pattern = method.getAnnotation(Path.class).value();
        }
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
            ? extends Service<HttpRequest, HttpResponse>> decorator(Method method, Class<?> clazz) {

        final List<DecoratorAndOrder> decorators = collectDecorators(clazz, method);

        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator = null;
        for (int i = decorators.size() - 1; i >= 0; i--) {
            final DecoratorAndOrder d = decorators.get(i);
            decorator = decorator == null ? d.decorator()
                                          : decorator.andThen(d.decorator());
        }
        return decorator == null ? Function.identity() : decorator;
    }

    /**
     * Returns a decorator list which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    @VisibleForTesting
    static List<DecoratorAndOrder> collectDecorators(Class<?> clazz, Method method) {
        final List<DecoratorAndOrder> decorators = new ArrayList<>();

        // Class-level decorators are applied before method-level decorators.
        collectDecorators(decorators, clazz.getAnnotations());
        collectDecorators(decorators, method.getAnnotations());

        // Sort decorators by "order" attribute values.
        decorators.sort(Comparator.comparing(DecoratorAndOrder::order));

        return decorators;
    }

    /**
     * Adds decorators to the specified {@code list}. Decorators which are annotated with {@link Decorator}
     * and user-defined decorators will be collected.
     */
    @SuppressWarnings("unchecked")
    private static void collectDecorators(List<DecoratorAndOrder> list, Annotation[] annotations) {
        if (annotations.length == 0) {
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
                final Annotation[] decorators = (Annotation[]) annotation.annotationType()
                                                                         .getMethod("value")
                                                                         .invoke(annotation);
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
        final DecoratorFactory d = annotation.annotationType().getAnnotation(DecoratorFactory.class);
        if (d == null) {
            return null;
        }

        // In case of user-defined decorator, we need to create a new decorator from its factory.
        @SuppressWarnings("unchecked")
        final DecoratorFactoryFunction<Annotation> factory = getInstance(d, DecoratorFactoryFunction.class);
        assert factory != null;

        // If the annotation has "order" attribute, we can use it when sorting decorators.
        int order = 0;
        try {
            final Object value = annotation.annotationType().getMethod("order").invoke(annotation);
            if (value instanceof Integer) {
                order = (Integer) value;
            }
        } catch (Throwable ignore) {
            // A user-defined decorator may not have an 'order' attribute.
            // If it does not exist, '0' is used by default.
        }
        return new DecoratorAndOrder(annotation, factory.newDecorator(annotation), order);
    }

    /**
     * Returns a new decorator which decorates a {@link Service} by {@link FunctionalDecoratingService}
     * and the specified {@link DecoratingServiceFunction}.
     */
    @SuppressWarnings("unchecked")
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(Decorator decorator) {
        return service -> new FunctionalDecoratingService<>(
                service, getInstance(decorator, DecoratingServiceFunction.class));
    }

    /**
     * Returns an exception handler list which is specified by {@link ExceptionHandler} annotations.
     */
    private static ImmutableList.Builder<ExceptionHandlerFunction> exceptionHandlers(Method targetMethod,
                                                                                     Class<?> targetClass) {
        return annotationValues(targetMethod, targetClass, ExceptionHandler.class,
                                ExceptionHandlerFunction.class);
    }

    /**
     * Returns a request converter list which is specified by {@link RequestConverter} annotations.
     */
    private static ImmutableList.Builder<RequestConverterFunction> requestConverters(Method targetMethod,
                                                                                     Class<?> targetClass) {
        return annotationValues(targetMethod, targetClass, RequestConverter.class,
                                RequestConverterFunction.class);
    }

    /**
     * Returns a response converter list which is specified by {@link ResponseConverter} annotations.
     */
    private static ImmutableList.Builder<ResponseConverterFunction> responseConverters(Method targetMethod,
                                                                                       Class<?> targetClass) {
        return annotationValues(targetMethod, targetClass, ResponseConverter.class,
                                ResponseConverterFunction.class);
    }

    /**
     * Returns an immutable list builder of objects which are specified as the {@code value} of annotation
     * {@code T}.
     */
    private static <T extends Annotation, R> ImmutableList.Builder<R> annotationValues(
            Method targetMethod, Class<?> targetClass, Class<T> annotationClass, Class<R> expectedType) {

        requireNonNull(annotationClass, "annotationClass");
        requireNonNull(targetMethod, "targetMethod");
        requireNonNull(targetClass, "targetClass");

        final ImmutableList.Builder<R> builder = new Builder<>();
        for (final T annotation : targetMethod.getAnnotationsByType(annotationClass)) {
            builder.add(getInstance(annotation, expectedType));
        }
        for (final T annotation : targetClass.getAnnotationsByType(annotationClass)) {
            builder.add(getInstance(annotation, expectedType));
        }
        return builder;
    }

    /**
     * Returns a cached instance of the specified {@link Class}.
     */
    @SuppressWarnings("unchecked")
    static <T> T getInstance(Annotation annotation, Class<T> expectedType) {
        try {
            final Class<? extends T> clazz = (Class<? extends T>) invokeValueMethod(annotation);
            return expectedType.cast(instanceCache.computeIfAbsent(clazz, type -> {
                try {
                    final Constructor<? extends T> constructor = clazz.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    return constructor.newInstance();
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
     * Returns an object which is returned by {@code value()} method of the specified annotation {@code a}.
     */
    private static Object invokeValueMethod(Annotation a) {
        try {
            return a.getClass().getMethod("value").invoke(a);
        } catch (Exception e) {
            throw new IllegalStateException("An annotation @" + a.getClass().getSimpleName() +
                                            " must have a 'value' method", e);
        }
    }

    /**
     * Returns a {@link AnnotatedHttpService} instance defined to {@code method} of {@code object} using
     * {@link Path} annotation.
     */
    private static AnnotatedHttpService build(String pathPrefix, Object object, Method method,
                                              List<ExceptionHandlerFunction> baseExceptionHandlers,
                                              List<RequestConverterFunction> baseRequestConverters,
                                              List<ResponseConverterFunction> baseResponseConverters) {

        final Set<Annotation> methodAnnotations = httpMethodAnnotations(method);
        if (methodAnnotations.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }

        final Set<HttpMethod> methods = toHttpMethods(methodAnnotations);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }

        final Class<?> clazz = object.getClass();
        final HttpHeaderPathMapping pathMapping =
                new HttpHeaderPathMapping(pathStringMapping(pathPrefix, method, methodAnnotations),
                                          methods, consumeTypes(method, clazz),
                                          produceTypes(method, clazz));

        final List<ExceptionHandlerFunction> eh =
                exceptionHandlers(method, clazz).addAll(baseExceptionHandlers)
                                                .addAll(defaultExceptionHandlers).build();
        final List<RequestConverterFunction> req =
                requestConverters(method, clazz).addAll(baseRequestConverters)
                                                .addAll(defaultRequestConverters).build();
        final List<ResponseConverterFunction> res =
                responseConverters(method, clazz).addAll(baseResponseConverters).build();

        final AnnotatedHttpServiceMethod function =
                new AnnotatedHttpServiceMethod(object, method, pathMapping, eh, req, res);
        final Set<String> parameterNames = function.pathParamNames();
        final Set<String> expectedParamNames = pathMapping.paramNames();
        if (!expectedParamNames.containsAll(parameterNames)) {
            final Set<String> missing = Sets.difference(parameterNames, expectedParamNames);
            throw new IllegalArgumentException("Missing @Param exists: " + missing);
        }
        if (!parameterNames.containsAll(expectedParamNames)) {
            final Set<String> missing = Sets.difference(expectedParamNames, parameterNames);
            logger.warn("Some path variables of the method '" + method.getName() +
                        "' of the class '" + clazz.getName() +
                        "' do not have their corresponding parameters annotated with @Param. " +
                        "They would not be automatically injected: " + missing);
        }

        return new AnnotatedHttpService(pathMapping, function, decorator(method, clazz));
    }

    /**
     * Returns the list of {@link AnnotatedHttpService} defined to {@code object} using {@link Path}
     * annotation.
     */
    static List<AnnotatedHttpService> build(String pathPrefix, Object object,
                                            Iterable<?> exceptionHandlersAndConverters) {

        ImmutableList.Builder<ExceptionHandlerFunction> exceptionHandlers = null;
        ImmutableList.Builder<RequestConverterFunction> requestConverters = null;
        ImmutableList.Builder<ResponseConverterFunction> responseConverters = null;

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
                      .map((Method method) -> build(pathPrefix, object, method, exceptionHandlerFunctions,
                                                    requestConverterFunctions, responseConverterFunctions))
                      .collect(toImmutableList());
    }

    private AnnotatedHttpServices() {}

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
            assert mapping instanceof GlobPathMapping || mapping instanceof RegexPathMapping
                    : "unexpected mapping type: " + mapping.getClass().getName();

            this.pathPrefix = pathPrefix;
            this.mapping = mapping;
            loggerName = loggerName(pathPrefix) + '.' + mapping.loggerName();
            meterTag = PrefixPathMapping.PREFIX + pathPrefix + ',' + mapping.meterTag();
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
            return '[' + PrefixPathMapping.PREFIX + pathPrefix + ", " + mapping + ']';
        }
    }

    /**
     * A default exception handler is used when a user does not specify exception handlers
     * by {@link ExceptionHandler} annotation.
     */
    private static class DefaultExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof IllegalArgumentException) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST);
            }

            if (cause instanceof HttpStatusException) {
                return HttpResponse.of(((HttpStatusException) cause).httpStatus());
            }

            if (cause instanceof HttpResponseException) {
                return ((HttpResponseException) cause).httpResponse();
            }

            return ExceptionHandlerFunction.DEFAULT.handleException(ctx, req, cause);
        }
    }

    /**
     * An internal class to hold a decorator with its order.
     */
    @VisibleForTesting
    static class DecoratorAndOrder {
        // Keep the specified annotation for testing purpose.
        private final Annotation annotation;
        private final Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator;
        private final int order;

        DecoratorAndOrder(Annotation annotation,
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
    }
}
