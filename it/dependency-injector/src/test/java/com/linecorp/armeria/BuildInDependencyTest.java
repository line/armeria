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

package com.linecorp.armeria;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;

import com.linecorp.armeria.internal.common.CreateIfMissing;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

class BuildInDependencyTest {
    static Collection<Class<?>> findAllClasses(String packageName) {
        return new Reflections(packageName, new SubTypesScanner(false))
                .getSubTypesOf(Object.class)
                .stream()
                .collect(toImmutableList());
    }

    @Test
    void test() {
        final List<Class<?>> classes = findAllClasses("com.linecorp.armeria")
                .stream()
                .filter(clazz -> (RequestConverterFunction.class.isAssignableFrom(clazz) ||
                                  ResponseConverterFunction.class.isAssignableFrom(clazz) ||
                                  DecoratorFactoryFunction.class.isAssignableFrom(clazz) ||
                                  ExceptionHandlerFunction.class.isAssignableFrom(clazz)) &&
                                 Modifier.isPublic(clazz.getModifiers()) &&
                                 !Modifier.isAbstract(clazz.getModifiers()) &&
                                 !Modifier.isInterface(clazz.getModifiers()) &&
                                 !ReflectionUtils.findConstructors(
                                         clazz, constructor -> constructor.getParameterCount() == 0).isEmpty())
                .collect(toImmutableList());
        assertThat(classes).allMatch(clazz -> clazz.getDeclaredAnnotation(CreateIfMissing.class) != null);
    }
}
