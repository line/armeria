/*
 * Copyright 2021 LINE Corporation
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.server.docs.AbstractDocServicePlugin;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A {@link DocServicePlugin} implementation that supports the {@link AbstractHttpService}.
 */
public final class HttpDocServicePlugin extends AbstractDocServicePlugin {

    @VisibleForTesting
    static final TypeSignature HTTP_RESPONSE = TypeSignature.ofBase(HttpResponse.class.getSimpleName());

    static final Set<HttpMethod> IGNORED_HTTP_METHODS = ImmutableSet.of(HttpMethod.CONNECT);

    static final Map<HttpMethod, String> METHOD_NAMES =
            HttpMethod.knownMethods()
                      .stream()
                      .filter(method -> !IGNORED_HTTP_METHODS.contains(method))
                      .collect(toImmutableMap(method -> method,
                                              method -> "do" + capitalize(method.name().toLowerCase())));

    private static String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(AbstractHttpService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs,
                                                      DocServiceFilter filter) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");

        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        for (ServiceConfig sc : serviceConfigs) {
            final AbstractHttpService service = sc.service().as(AbstractHttpService.class);
            if (service == null) {
                continue;
            }
            final String className = service.getClass().getName();
            for (HttpMethod method : sc.route().methods()) {
                if (IGNORED_HTTP_METHODS.contains(method)) {
                    continue;
                }
                final String methodName = METHOD_NAMES.get(method);
                if (!filter.test(name(), className, methodName)) {
                    continue;
                }
                addMethodInfo(methodInfos, sc.virtualHost().hostnamePattern(), service, sc.route(),
                              method);
            }
        }
        return generate(ImmutableMap.of(), methodInfos);
    }

    @Override
    public String toString() {
        return HttpDocServicePlugin.class.getSimpleName();
    }

    private static void addMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos, String hostnamePattern,
                                      AbstractHttpService service, Route route, HttpMethod httpMethod) {
        final EndpointInfo endpoint = endpointInfo(route, hostnamePattern);
        final Class<?> clazz = service.getClass();
        final String methodName = METHOD_NAMES.get(httpMethod);
        final MethodInfo methodInfo = new MethodInfo(
                methodName, HTTP_RESPONSE, ImmutableList.of(), ImmutableList.of(), // Ignore exceptions.
                ImmutableList.of(endpoint),
                httpMethod, null);
        methodInfos.computeIfAbsent(clazz, unused -> new HashSet<>()).add(methodInfo);
    }

    @VisibleForTesting
    static ServiceSpecification generate(Map<Class<?>, String> serviceDescription,
                                         Map<Class<?>, Set<MethodInfo>> methodInfos) {
        final Set<ServiceInfo> serviceInfos = methodInfos
                .entrySet().stream()
                .map(entry -> {
                    final Class<?> service = entry.getKey();
                    return new ServiceInfo(service.getName(), entry.getValue(),
                                           serviceDescription.get(service));
                })
                .collect(toImmutableSet());

        return ServiceSpecification.generate(serviceInfos, typeSignature -> null /* ignored */);
    }

    @Override
    public int order() {
        return 1;
    }
}
