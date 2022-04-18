/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.getSuperTypes;
import static org.reflections.ReflectionUtils.includeObject;
import static org.reflections.util.Utils.isEmpty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;

/**
 * Utility class which is used by {@link GrpcServiceBuilder} to get all sorted methods
 * from a bottom service class.
 */
final class InternalReflectionUtils {

    /**
     * get all methods of given type which are sorted from bottom class, up the super class hierarchy,
     * optionally filtered by predicates.
     */
    static List<Method> getAllSortedMethods(final Class<?> type,
                                            Predicate<? super Method>... predicates) {
        final ImmutableList.Builder<Method> result = ImmutableList.builder();
        for (Class<?> t : getAllSortedSuperTypes(type)) {
            result.addAll(getMethods(t, predicates));
        }
        return result.build();
    }

    private static Iterable<Class<?>> getAllSortedSuperTypes(final Class<?> type,
                                                             Predicate<? super Class<?>>... predicates) {
        final List<Class<?>> result = new ArrayList<>();
        if (includeObject || !type.equals(Object.class)) {
            result.add(type);
            for (Class<?> supertype : getSuperTypes(type)) {
                getAllSortedSuperTypes(supertype).forEach(result::add);
            }
        }
        return filter(result, predicates);
    }

    private static <T> List<T> filter(List<T> elements, Predicate<? super T>... predicates) {
        return isEmpty(predicates) ? elements
                                   : elements.stream()
                                             .filter(Predicates.and(predicates)::apply)
                                             .collect(toImmutableList());
    }

    private InternalReflectionUtils() {}
}
