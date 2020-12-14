/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import com.google.common.collect.ImmutableList;

public abstract class AbstractMethodChainingTest {

    private final List<String> ignoredClasses;

    protected AbstractMethodChainingTest(String... ignoredClasses) {
        this.ignoredClasses = ImmutableList.copyOf(ignoredClasses);
    }

    @Test
    void methodChaining() {
        final String packageName = getClass().getPackage().getName();
        findAllClasses(packageName).stream()
                                   .map(ReflectionUtils::forName)
                                   .filter(this::filterClass)
                                   .forEach(clazz -> {
                                       final List<Method> methods = getAllMethods(clazz);
                                       for (Method m : methods) {
                                           try {
                                               final Method overriddenMethod =
                                                       clazz.getDeclaredMethod(m.getName(),
                                                                               m.getParameterTypes());
                                               assertThat(overriddenMethod.getReturnType()).isSameAs(clazz);
                                           } catch (NoSuchMethodException e) {
                                               // ignored
                                           }
                                       }
                                   });
    }

    private static Collection<String> findAllClasses(String packageName) {
        final Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .setUrls(ClasspathHelper.forPackage(packageName))
                        .setScanners(new SubTypesScanner(false))
        );
        return reflections.getStore().get("SubTypesScanner").values();
    }

    private boolean filterClass(Class<?> clazz) {
        return declaredInTestClass(clazz) &&
               clazz.getName().endsWith("Builder") &&
               !ignoredClasses.contains(clazz.getName());
    }

    private static boolean declaredInTestClass(Class<?> clazz) {
        final Class<?> declaringClass = clazz.getDeclaringClass();
        if (declaringClass == null) {
            return true;
        }
        return !declaringClass.getSimpleName().endsWith("Test");
    }

    private static List<Method> getAllMethods(Class<?> clazz) {
        final Set<Class<?>> allSuperTypes = ReflectionUtils.getAllSuperTypes(clazz, input -> input != clazz);
        return allSuperTypes.stream()
                            .flatMap(sc -> Arrays.stream(sc.getMethods()))
                            .distinct()
                            .filter(m -> m.getReturnType() == m.getDeclaringClass())
                            .collect(toImmutableList());
    }
}
