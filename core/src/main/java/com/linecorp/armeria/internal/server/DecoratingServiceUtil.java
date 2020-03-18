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

package com.linecorp.armeria.internal.server;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.function.Function;

import org.reflections.ReflectionUtils;

import com.google.common.base.Predicate;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

public final class DecoratingServiceUtil {

    private static final Predicate<Method> isOverriddenAsMethod = method -> {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        return "as".equals(method.getName()) &&
               Modifier.isPublic(method.getModifiers()) && !method.isDefault() &&
               parameterTypes.length == 1 && parameterTypes[0].getName().equals(Class.class.getName());
    };

    private static final Predicate<Method> isOverriddenServiceAddedMethod = method -> {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        return "serviceAdded".equals(method.getName()) &&
               Modifier.isPublic(method.getModifiers()) && !method.isDefault() &&
               parameterTypes.length == 1 && parameterTypes[0].getName().equals(ServiceConfig.class.getName());
    };

    private static final Predicate<Method> isOverriddenAsOrServiceAddedMethod =
            method -> isOverriddenAsMethod.test(method) || isOverriddenServiceAddedMethod.test(method);

    private static final Function<? super HttpService, ? extends HttpService> noopDecorator =
            delegate -> new SimpleDecoratingHttpService(delegate) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return delegate().serve(ctx, req);
                }
            };

    /**
     * Validates whether the specified {@code decorator} overrides {@link Service#as(Class)} and
     * {@link Service#serviceAdded(ServiceConfig)} properly.
     */
    public static <I extends Request, O extends Response>
    void validateDecorator(Service<I, O> decorator) {
        final Set<Method> methods = ReflectionUtils.getAllMethods(decorator.getClass(),
                                                                  isOverriddenAsOrServiceAddedMethod);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(
                    "decorator should override Service.as() and Service.serviceAdded(): " + decorator);
        }

        methods.stream()
               .filter(isOverriddenAsMethod)
               .findFirst()
               .orElseThrow(() -> new IllegalArgumentException(
                       "decorator should override Service.as(): " + decorator));

        methods.stream()
               .filter(isOverriddenServiceAddedMethod)
               .findFirst()
               .orElseThrow(() -> new IllegalArgumentException(
                       "decorator should override Service.serviceAdded(): " + decorator));
    }

    public static Function<? super HttpService, ? extends HttpService> noopDecorator() {
        return noopDecorator;
    }

    private DecoratingServiceUtil() {}
}
