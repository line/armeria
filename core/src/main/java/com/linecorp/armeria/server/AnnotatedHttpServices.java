/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.server.annotation.Converter;
import com.linecorp.armeria.server.annotation.Converter.Unspecified;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Head;
import com.linecorp.armeria.server.annotation.Options;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.Trace;

/**
 * Builds a list of {@link AnnotatedHttpService}s from a Java object.
 */
final class AnnotatedHttpServices {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServices.class);

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
                     .collect(toImmutableList());
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
     * Returns the {@link PathMapping} instance mapped to {@code method}.
     */
    private static PathMapping pathMapping(String pathPrefix, Method method,
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

        Path path = method.getAnnotation(Path.class);
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
                            "Only one path can be specified. (" + pattern + ", " + p + ")");
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
        Converter[] converters = method.getAnnotationsByType(Converter.class);
        if (converters.length == 0) {
            return null;
        }
        if (converters.length == 1) {
            Converter converter = converters[0];
            if (converter.target() != Unspecified.class) {
                throw new IllegalArgumentException(
                        "@Converter annotation can't be marked on a method with a target specified.");
            }
            try {
                return converter.value().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        throw new IllegalArgumentException("@Converter annotation can't be repeated on a method.");
    }

    /**
     * Returns a mapping from {@link Class} to {@link ResponseConverter} instances from {@link Converter}
     * annotations of the given {@code clazz}. The {@link Converter} annotation marked on {@code clazz} must
     * specify the target class, except {@link Object}.class.
     */
    private static Map<Class<?>, ResponseConverter> converters(Class<?> clazz) {
        Converter[] converters = clazz.getAnnotationsByType(Converter.class);
        ImmutableMap.Builder<Class<?>, ResponseConverter> builder = ImmutableMap.builder();
        for (Converter converter : converters) {
            Class<?> target = converter.target();
            if (target == Unspecified.class) {
                throw new IllegalArgumentException(
                        "@Converter annotation must have a target type specified.");
            }
            try {
                ResponseConverter instance = converter.value().newInstance();
                builder.put(target, instance);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return builder.build();
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

        final PathMapping pathMapping = pathMapping(pathPrefix, method, methodAnnotations);
        final AnnotatedHttpServiceMethod function = new AnnotatedHttpServiceMethod(object, method, pathMapping);

        final Set<String> parameterNames = function.pathParamNames();
        final Set<String> expectedParamNames = pathMapping.paramNames();
        if (!expectedParamNames.containsAll(parameterNames)) {
            Set<String> missing = Sets.difference(parameterNames, expectedParamNames);
            throw new IllegalArgumentException("Missing @Param exists: " + missing);
        }

        final ResponseConverter converter = converter(method);
        if (converter != null) {
            return new AnnotatedHttpService(methods, pathMapping, function.withConverter(converter));
        }

        final ImmutableMap<Class<?>, ResponseConverter> newConverters =
                ImmutableMap.<Class<?>, ResponseConverter>builder()
                        .putAll(converters) // Pre-defined converters
                        .putAll(converters(method.getDeclaringClass())) // Converters given by @Converters
                        .build();

        return new AnnotatedHttpService(methods, pathMapping, function.withConverters(newConverters));
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
    private static final class PrefixAddingPathMapping extends AbstractPathMapping {

        private final String pathPrefix;
        private final PathMapping mapping;

        PrefixAddingPathMapping(String pathPrefix, PathMapping mapping) {
            assert mapping instanceof GlobPathMapping || mapping instanceof RegexPathMapping
                    : "unexpected mapping type: " + mapping.getClass().getName();

            this.pathPrefix = pathPrefix;
            this.mapping = mapping;
        }

        @Override
        protected PathMappingResult doApply(String path, @Nullable String query) {
            if (!path.startsWith(pathPrefix)) {
                return PathMappingResult.empty();
            }

            final PathMappingResult result = mapping.apply(path.substring(pathPrefix.length() - 1), query);
            if (result.isPresent()) {
                return PathMappingResult.of(path, query, result.pathParams());
            } else {
                return PathMappingResult.empty();
            }
        }

        @Override
        public Set<String> paramNames() {
            return mapping.paramNames();
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
