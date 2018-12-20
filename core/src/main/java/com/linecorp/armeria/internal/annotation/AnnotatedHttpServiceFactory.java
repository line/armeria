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
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getAllAnnotations;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.NoParameterException;
import com.linecorp.armeria.server.AbstractPathMapping;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.PathMappingContext;
import com.linecorp.armeria.server.PathMappingResult;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.ByteArrayResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.ConsumeTypes;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.ConsumesGroup;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ExceptionHandlers;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProduceType;
import com.linecorp.armeria.server.annotation.ProduceTypes;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesGroup;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverters;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverters;
import com.linecorp.armeria.server.annotation.StringResponseConverterFunction;
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
     * A default {@link ResponseConverterFunction}s.
     */
    private static final List<ResponseConverterFunction> defaultResponseConverters =
            ImmutableList.of(new JacksonResponseConverterFunction(),
                             new StringResponseConverterFunction(),
                             new ByteArrayResponseConverterFunction());

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
                exceptionHandlers(method, clazz).addAll(baseExceptionHandlers)
                                                .add(defaultExceptionHandler).build();
        final List<RequestConverterFunction> req =
                requestConverters(method, clazz).addAll(baseRequestConverters).build();
        final List<ResponseConverterFunction> res =
                responseConverters(method, clazz).addAll(baseResponseConverters)
                                                 .addAll(defaultResponseConverters).build();

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
        return new AnnotatedHttpServiceElement(pathMapping,
                                               new AnnotatedHttpService(object, method, resolvers, eh,
                                                                        res, pathMapping),
                                               decorator(method, clazz));
    }

    /**
     * Returns the list of {@link Path} annotated methods.
     */
    private static List<Method> requestMappingMethods(Object object) {
        return getAllMethods(object.getClass(), withModifier(Modifier.PUBLIC))
                .stream()
                .filter(m -> getAllAnnotations(m).stream()
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
        final Order order = findAnnotation(method, Order.class).orElse(null);
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
        return getAllAnnotations(method)
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
        final List<MediaType> mediaTypes = consumableMediaTypes(method);
        return mediaTypes.isEmpty() ? consumableMediaTypes(clazz) : mediaTypes;
    }

    private static List<MediaType> consumableMediaTypes(AnnotatedElement element) {
        final List<MediaType> mediaTypes = new ArrayList<>();

        for (final Annotation annotation : getAllAnnotations(element)) {
            if (annotation instanceof ConsumesGroup) {
                Arrays.stream(((ConsumesGroup) annotation).value())
                      .forEach(e -> addConsumableMediaType(mediaTypes, MediaType.parse(e.value())));
            } else if (annotation instanceof Consumes) {
                addConsumableMediaType(mediaTypes, MediaType.parse(((Consumes) annotation).value()));
            } else if (annotation instanceof ConsumeTypes) {
                Arrays.stream(((ConsumeTypes) annotation).value())
                      .forEach(e -> addConsumableMediaType(mediaTypes, MediaType.parse(e.value())));
            } else if (annotation instanceof ConsumeType) {
                addConsumableMediaType(mediaTypes, MediaType.parse(((ConsumeType) annotation).value()));
            } else {
                findAnnotations(annotation.annotationType(), Consumes.class)
                        .forEach(e -> addConsumableMediaType(mediaTypes, MediaType.parse(e.value())));
            }
        }
        return mediaTypes;
    }

    private static void addConsumableMediaType(List<MediaType> mediaTypes, MediaType newMediaType) {
        addMediaType(mediaTypes, newMediaType, Consumes.class, true);
    }

    /**
     * Returns the list of {@link MediaType}s specified by {@link Produces} annotation.
     */
    private static List<MediaType> producibleMediaTypes(Method method, Class<?> clazz) {
        final List<MediaType> mediaTypes = producibleMediaTypes(method);
        return mediaTypes.isEmpty() ? producibleMediaTypes(clazz) : mediaTypes;
    }

    private static List<MediaType> producibleMediaTypes(AnnotatedElement element) {
        final List<MediaType> mediaTypes = new ArrayList<>();

        for (final Annotation annotation : getAllAnnotations(element)) {
            if (annotation instanceof ProducesGroup) {
                Arrays.stream(((ProducesGroup) annotation).value())
                      .forEach(e -> addProducibleMediaType(mediaTypes, MediaType.parse(e.value())));
            } else if (annotation instanceof Produces) {
                addProducibleMediaType(mediaTypes, MediaType.parse(((Produces) annotation).value()));
            } else if (annotation instanceof ProduceTypes) {
                Arrays.stream(((ProduceTypes) annotation).value())
                      .forEach(e -> addProducibleMediaType(mediaTypes, MediaType.parse(e.value())));
            } else if (annotation instanceof ProduceType) {
                addProducibleMediaType(mediaTypes, MediaType.parse(((ProduceType) annotation).value()));
            } else {
                findAnnotations(annotation.annotationType(), Produces.class)
                        .forEach(e -> addProducibleMediaType(mediaTypes, MediaType.parse(e.value())));
            }
        }
        return mediaTypes;
    }

    private static void addProducibleMediaType(List<MediaType> mediaTypes, MediaType newMediaType) {
        addMediaType(mediaTypes, newMediaType, Produces.class, false);
    }

    private static void addMediaType(List<MediaType> mediaTypes, MediaType newMediaType,
                                     Class<?> clazz, boolean allowWildcard) {
        if (!allowWildcard && newMediaType.hasWildcard()) {
            throw new IllegalArgumentException('@' + clazz.getSimpleName() + " must not have a wildcard: " +
                                               newMediaType);
        }
        if (mediaTypes.stream().anyMatch(e -> e.equals(newMediaType))) {
            throw new IllegalArgumentException("Duplicated media type for @" + clazz.getSimpleName() + ": " +
                                               newMediaType);
        }
        mediaTypes.add(newMediaType);
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
        String pattern = findAnnotation(method, Path.class).map(Path::value)
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
    @Nullable
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
                findAnnotation(annotation.annotationType(), DecoratorFactory.class).orElse(null);
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
     * Returns an exception handler list which is specified by {@link ExceptionHandler} annotations.
     */
    private static Builder<ExceptionHandlerFunction> exceptionHandlers(Method targetMethod,
                                                                       Class<?> targetClass) {
        return annotationValues(targetMethod, targetClass, ExceptionHandler.class, ExceptionHandlers.class,
                                ExceptionHandlerFunction.class);
    }

    /**
     * Returns a request converter list which is specified by {@link RequestConverter} annotations.
     */
    private static Builder<RequestConverterFunction> requestConverters(Method targetMethod,
                                                                       Class<?> targetClass) {
        return annotationValues(targetMethod, targetClass, RequestConverter.class, RequestConverters.class,
                                RequestConverterFunction.class);
    }

    /**
     * Returns a response converter list which is specified by {@link ResponseConverter} annotations.
     */
    private static Builder<ResponseConverterFunction> responseConverters(Method targetMethod,
                                                                         Class<?> targetClass) {
        return annotationValues(targetMethod, targetClass, ResponseConverter.class, ResponseConverters.class,
                                ResponseConverterFunction.class);
    }

    /**
     * Returns an immutable list builder of objects which are specified as the {@code value} of annotation
     * {@code T}.
     */
    private static <T extends Annotation, R> Builder<R> annotationValues(
            Method targetMethod, Class<?> targetClass, Class<T> annotationClass, Class<?> repeatableClass,
            Class<R> expectedType) {

        requireNonNull(annotationClass, "annotationClass");
        requireNonNull(targetMethod, "targetMethod");
        requireNonNull(targetClass, "targetClass");

        final Builder<R> builder = new Builder<>();
        annotationValues0(builder, targetMethod, annotationClass, repeatableClass, expectedType);
        getClasses(targetClass, new Builder<>()).forEach(
                target -> annotationValues0(builder, target, annotationClass, repeatableClass, expectedType));
        return builder;
    }

    private static <T extends Annotation, R> void annotationValues0(
            Builder<R> builder, AnnotatedElement target, Class<T> annotationClass, Class<?> repeatableClass,
            Class<R> expectedType) {
        for (final Annotation annotation : target.getAnnotations()) {
            final Class<? extends Annotation> type = annotation.annotationType();
            if (type == annotationClass) {
                builder.add(getInstance(annotation, expectedType));
            } else if (type == repeatableClass) {
                @SuppressWarnings("unchecked")
                final T[] annotations = (T[]) invokeValueMethod(annotation);
                Arrays.stream(annotations).forEach(a -> builder.add(getInstance(a, expectedType)));
            }
        }
    }

    private static List<Class<?>> getClasses(Class<?> clazz, Builder<Class<?>> builder) {
        final Class<?> superClass = clazz.getSuperclass();
        if (superClass == null || superClass == Object.class) {
            builder.add(clazz);
            return builder.build();
        }

        // Subclass first.
        builder.add(clazz);
        return getClasses(superClass, builder);
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

                    final Constructor<? extends T> constructor =
                            Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);
                    assert constructor != null : "No default constructor is found from " + clazz.getName();
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
     * Returns a cached instance of the specified {@link Class}.
     */
    @SuppressWarnings("unchecked")
    static <T> T getInstance(Class<T> clazz) {
        return (T) instanceCache.computeIfAbsent(clazz, type -> {
            try {
                final Constructor<? extends T> constructor =
                        Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);
                assert constructor != null : "No default constructor is found from " + clazz.getName();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("A class must have an accessible default constructor: " +
                                                clazz.getName(), e);
            }
        });
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
        final Optional<Description> description = findAnnotation(annotatedElement, Description.class);
        if (description.isPresent()) {
            final String value = description.get().value();
            if (DefaultValues.isSpecified(value)) {
                checkArgument(!value.isEmpty(), "value is empty");
                return value;
            }
        }
        return null;
    }

    /**
     * Returns an {@link Annotation} of the specified {@link Class}.
     */
    private static <T extends Annotation> Optional<T> findAnnotation(AnnotatedElement element,
                                                                     Class<T> target) {
        final List<T> list = findAnnotations(element, target);
        return list.isEmpty() ? Optional.empty()
                              : Optional.of(list.get(0));
    }

    /**
     * Returns {@link Annotation}s of the specified {@link Class}.
     */
    private static <T extends Annotation> List<T> findAnnotations(AnnotatedElement element,
                                                                  Class<T> target) {
        return getAllAnnotations(element)
                .stream()
                .filter(annotation -> annotation.annotationType() == target)
                .map(target::cast)
                .collect(toImmutableList());
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
