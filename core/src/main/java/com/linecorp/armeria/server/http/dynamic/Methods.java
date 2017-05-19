/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.dynamic;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.toImmutableEnumSet;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.PathParamExtractor;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Provides various utility functions for {@link Method} object, related to {@link DynamicHttpService}.
 */
final class Methods {

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
                     .filter(m -> m.getAnnotation(Path.class) != null)
                     .collect(toImmutableList());
    }

    /**
     * Returns {@link EnumSet} instance of {@link HttpMethod} mapped to {@code method}. If no specific HTTP
     * Method is mapped to given {@code method}, it is regarded as all HTTP Methods are mapped to on it.
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
    private static Set<HttpMethod> httpMethods(Method method) {
        return Arrays.stream(method.getAnnotations())
                     .map(annotation -> HTTP_METHOD_MAP.get(annotation.annotationType()))
                     .filter(Objects::nonNull)
                     .collect(toImmutableEnumSet());
    }

    /**
     * Returns the {@link PathParamExtractor} instance mapped to {@code method}.
     */
    private static PathParamExtractor pathParamExtractor(Method method) {
        Path mapping = method.getAnnotation(Path.class);
        String mappedTo = mapping.value();
        return new PathParamExtractor(mappedTo);
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
            if (converter.target() != Object.class) {
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
            if (target == Object.class) {
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
     * Returns the array of {@link ParameterEntry}, which holds the type and {@link PathParam} value.
     */
    static List<ParameterEntry> parameterEntries(Method method) {
        return Arrays.stream(method.getParameters())
                     .map(p -> {
                         if (p.getType().isAssignableFrom(ServiceRequestContext.class) ||
                             p.getType().isAssignableFrom(HttpRequest.class)) {
                             return new ParameterEntry(p.getType(), null);
                         } else {
                             return new ParameterEntry(p.getType(), p.getAnnotation(PathParam.class).value());
                         }
                     })
                     .collect(toImmutableList());
    }

    /**
     * Returns a {@link DynamicHttpFunctionEntry} instance defined to {@code method} of {@code object} using
     * {@link Path} annotation.
     */
    private static DynamicHttpFunctionEntry entry(Object object, Method method,
                                                  Map<Class<?>, ResponseConverter> converters) {
        Set<HttpMethod> methods = httpMethods(method);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("HTTP Method specification is missing: " + method.getName());
        }
        PathParamExtractor pathParamExtractor = pathParamExtractor(method);
        DynamicHttpFunctionImpl function = new DynamicHttpFunctionImpl(object, method);

        Set<String> parameterNames = function.pathParamNames();
        Set<String> pathVariableNames = pathParamExtractor.variables();
        if (!pathVariableNames.containsAll(parameterNames)) {
            Set<String> missing = Sets.difference(parameterNames, pathVariableNames);
            throw new IllegalArgumentException("Missing @PathParam exists: " + missing);
        }

        ResponseConverter converter = converter(method);
        if (converter != null) {
            return new DynamicHttpFunctionEntry(methods, pathParamExtractor,
                                                DynamicHttpFunctions.of(function, converter));
        } else {
            Map<Class<?>, ResponseConverter> converterMap = new HashMap<>();
            // Pre-defined converters
            converterMap.putAll(converters);
            // Converters given by @Converter annotation
            converterMap.putAll(converters(method.getDeclaringClass()));
            return new DynamicHttpFunctionEntry(methods, pathParamExtractor,
                                                DynamicHttpFunctions.of(function, converterMap));
        }
    }

    /**
     * Returns the list of {@link DynamicHttpFunctionEntry} defined to {@code object} using {@link Path}
     * annotation.
     */
    static List<DynamicHttpFunctionEntry> entries(Object object, Map<Class<?>, ResponseConverter> converters) {
        return Methods.requestMappingMethods(object)
                      .stream()
                      .map((Method method) -> entry(object, method, converters))
                      .collect(toImmutableList());
    }

    private Methods() {}
}
