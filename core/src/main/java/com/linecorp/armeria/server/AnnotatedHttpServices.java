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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.toImmutableEnumSet;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static com.linecorp.armeria.server.AbstractPathMapping.ensureAbsolutePath;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.ConsumeTypes;
import com.linecorp.armeria.server.annotation.Converter;
import com.linecorp.armeria.server.annotation.Converter.Unspecified;
import com.linecorp.armeria.server.annotation.Decorate;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Order;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProduceType;
import com.linecorp.armeria.server.annotation.ProduceTypes;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.Trace;

/**
 * Builds a list of {@link AnnotatedHttpService}s from a Java object.
 */
final class AnnotatedHttpServices {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServices.class);

    /**
     * A {@link DecoratingServiceFunction} map for reusing.
     */
    private static final ConcurrentMap<
            Class<? extends DecoratingServiceFunction<HttpRequest, HttpResponse>>,
            DecoratingServiceFunction<HttpRequest, HttpResponse>>
            decoratingServiceFunctions = new ConcurrentHashMap<>();

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
            try {
                final String p = (String) a.getClass().getMethod("value").invoke(a);
                if (DefaultValues.isUnspecified(p)) {
                    continue;
                }
                if (pattern != null) {
                    throw new IllegalArgumentException(
                            "Only one path can be specified. (" + pattern + ", " + p + ')');
                }
                pattern = p;
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                logger.debug("Invoking annotation 'value' method failed: {}", e);
            }
        }
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException(
                    "A path pattern should be specified by @Path or HTTP method annotations.");
        }
        return pattern;
    }

    /**
     * Returns the {@link ResponseConverter} instance from {@link Converter} annotation of the given
     * {@code method}. The {@link Converter} annotation marked on a method can't be repeated and should not
     * specify the target class.
     */
    private static ResponseConverter converter(Method method) {
        final Converter[] converters = method.getAnnotationsByType(Converter.class);
        if (converters.length == 0) {
            return null;
        }
        if (converters.length == 1) {
            final Converter converter = converters[0];
            if (converter.target() != Unspecified.class) {
                throw new IllegalArgumentException(
                        "@Converter annotation can't be marked on a method with a target specified.");
            }
            return newInstance(converter.value());
        }

        throw new IllegalArgumentException("@Converter annotation can't be repeated on a method.");
    }

    /**
     * Returns a mapping from {@link Class} to {@link ResponseConverter} instances from {@link Converter}
     * annotations of the given {@code clazz}. The {@link Converter} annotation marked on {@code clazz} must
     * specify the target class, except {@link Object}.class.
     */
    private static Map<Class<?>, ResponseConverter> converters(Class<?> clazz) {
        final Converter[] converters = clazz.getAnnotationsByType(Converter.class);
        final ImmutableMap.Builder<Class<?>, ResponseConverter> builder = ImmutableMap.builder();
        for (Converter converter : converters) {
            final Class<?> target = converter.target();
            if (target == Unspecified.class) {
                throw new IllegalArgumentException(
                        "@Converter annotation must have a target type specified.");
            }
            builder.put(target, newInstance(converter.value()));
        }
        return builder.build();
    }

    /**
     * Returns a decorator chain which is specified by {@link Decorate} annotations.
     */
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator(Method method) {

        final Decorate decorate = method.getAnnotation(Decorate.class);
        if (decorate == null) {
            return Function.identity();
        }

        final Class<? extends DecoratingServiceFunction<HttpRequest, HttpResponse>>[] c = decorate.value();
        if (c.length == 0) {
            return Function.identity();
        }

        // Respect the order of decorators which is specified by a user. The first one is first applied.
        Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> decorator = newDecorator(c[c.length - 1]);
        for (int i = c.length - 2; i >= 0; i--) {
            decorator = decorator.andThen(newDecorator(c[i]));
        }
        return decorator;
    }

    /**
     * Returns a new decorator which decorates a {@link Service} by {@link FunctionalDecoratingService}
     * and the specified {@link DecoratingServiceFunction}.
     */
    private static Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> newDecorator(
            Class<? extends DecoratingServiceFunction<HttpRequest, HttpResponse>> clazz) {
        return service -> new FunctionalDecoratingService<>(
                service, decoratingServiceFunctions.computeIfAbsent(clazz, AnnotatedHttpServices::newInstance));
    }

    /**
     * Returns a new instance of the specified {@link Class}.
     */
    private static <T> T newInstance(Class<? extends T> clazz) {
        try {
            final Constructor<? extends T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("A decorator function class specified in @" +
                                            Decorate.class.getSimpleName() +
                                            " annotation must have an accessible default constructor: " +
                                            clazz.getName(), e);
        }
    }

    /**
     * Returns a {@link AnnotatedHttpService} instance defined to {@code method} of {@code object} using
     * {@link Path} annotation.
     */
    private static AnnotatedHttpService build(String pathPrefix, Object object, Method method,
                                              Map<Class<?>, ResponseConverter> converters) {

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
                                          methods, consumeTypes(method, clazz), produceTypes(method, clazz));

        final AnnotatedHttpServiceMethod function = new AnnotatedHttpServiceMethod(object, method, pathMapping);

        final Set<String> parameterNames = function.pathParamNames();
        final Set<String> expectedParamNames = pathMapping.paramNames();
        if (!expectedParamNames.containsAll(parameterNames)) {
            Set<String> missing = Sets.difference(parameterNames, expectedParamNames);
            throw new IllegalArgumentException("Missing @Param exists: " + missing);
        }

        final ResponseConverter converter = converter(method);
        if (converter != null) {
            return new AnnotatedHttpService(pathMapping, function.withConverter(converter),
                                            decorator(method));
        }

        final ImmutableMap<Class<?>, ResponseConverter> newConverters =
                ImmutableMap.<Class<?>, ResponseConverter>builder()
                        .putAll(converters) // Pre-defined converters
                        .putAll(converters(method.getDeclaringClass())) // Converters given by @Converters
                        .build();

        return new AnnotatedHttpService(pathMapping, function.withConverters(newConverters),
                                        decorator(method));
    }

    /**
     * Returns the list of {@link AnnotatedHttpService} defined to {@code object} using {@link Path}
     * annotation.
     */
    static List<AnnotatedHttpService> build(String pathPrefix, Object object,
                                            Map<Class<?>, ResponseConverter> converters) {

        final List<Method> methods = requestMappingMethods(object);
        return methods.stream()
                      .map((Method method) -> build(pathPrefix, object, method, converters))
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
}
