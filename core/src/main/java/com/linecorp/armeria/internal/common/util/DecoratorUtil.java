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

package com.linecorp.armeria.internal.common.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.reflections.ReflectionUtils;

import com.google.common.base.Predicate;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;

public final class DecoratorUtil {

    private static Predicate<Method> isOverriddenAsMethod = method -> {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        return "as".equals(method.getName()) &&
               Modifier.isPublic(method.getModifiers()) &&
               !method.isDefault() &&
               parameterTypes.length == 1 &&
               parameterTypes[0].getName().equals(Class.class.getName());
    };

    private static Predicate<Method> isOverriddenServiceAddedMethod = method -> {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        return "serviceAdded".equals(method.getName()) &&
               Modifier.isPublic(method.getModifiers()) &&
               !method.isDefault() &&
               parameterTypes.length == 1 &&
               parameterTypes[0].getName().equals(ServiceConfig.class.getName());
    };

    /**
     * Validates whether the specified {@code decorator} overrides {@link Service#as(Class)} and
     * {@link Service#serviceAdded(ServiceConfig)} properly.
     */
    public static <I extends Request, O extends Response>
    void validateServiceDecorator(Service<I, O> decorator) {
        if (ReflectionUtils.getAllMethods(decorator.getClass(), isOverriddenAsMethod).isEmpty()) {
            throw new IllegalArgumentException("decorator should override Service.as(): " + decorator);
        }
        if (ReflectionUtils.getAllMethods(decorator.getClass(), isOverriddenServiceAddedMethod).isEmpty()) {
            throw new IllegalArgumentException(
                    "decorator should override Service.serviceAdded(): " + decorator);
        }
    }

    /**
     * Validates whether the specified {@code decorator} overrides {@link Client#as(Class)} properly.
     */
    public static <I extends Request, O extends Response> void validateClientDecorator(Client<I, O> decorator) {
        if (ReflectionUtils.getAllMethods(decorator.getClass(), isOverriddenAsMethod).isEmpty()) {
            throw new IllegalArgumentException("decorator should override Client.as(): " + decorator);
        }
    }

    private DecoratorUtil() {}
}
