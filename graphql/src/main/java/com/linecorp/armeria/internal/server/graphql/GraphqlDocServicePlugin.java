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

package com.linecorp.armeria.internal.server.graphql;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.graphql.protocol.AbstractGraphqlService;

/**
 * A {@link DocServicePlugin} implementation that supports the {@link AbstractGraphqlService}.
 */
public final class GraphqlDocServicePlugin implements DocServicePlugin {

    @VisibleForTesting
    static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    static final TypeSignature MAP = TypeSignature.ofBase("map");
    @VisibleForTesting
    static final TypeSignature JSON = TypeSignature.ofBase("json");

    static final String DEFAULT_METHOD_NAME = "doPost";

    @Override
    public String name() {
        return "graphql";
    }

    @Override
    public Set<Class<? extends Service<?, ?>>> supportedServiceTypes() {
        return ImmutableSet.of(AbstractGraphqlService.class);
    }

    @Override
    public ServiceSpecification generateSpecification(Set<ServiceConfig> serviceConfigs,
                                                      DocServiceFilter filter) {
        requireNonNull(serviceConfigs, "serviceConfigs");
        requireNonNull(filter, "filter");

        final Map<Class<?>, Set<MethodInfo>> methodInfos = new HashMap<>();
        final Map<Class<?>, String> serviceDescription = new HashMap<>();
        serviceConfigs.forEach(sc -> {
            final AbstractGraphqlService service = sc.service().as(AbstractGraphqlService.class);
            if (service != null) {
                final String className = service.getClass().getName();
                final String methodName = DEFAULT_METHOD_NAME;
                if (!filter.test(name(), className, methodName)) {
                    return;
                }
                addMethodInfo(methodInfos, sc.virtualHost().hostnamePattern(), service, sc.route());
            }
        });

        return generate(serviceDescription, methodInfos);
    }

    private static void addMethodInfo(Map<Class<?>, Set<MethodInfo>> methodInfos,
                                      String hostnamePattern, AbstractGraphqlService service, Route route) {
        final EndpointInfo endpoint = endpointInfo(route, hostnamePattern);
        final String name = DEFAULT_METHOD_NAME;
        final List<FieldInfo> fieldInfos = fieldInfos();
        final Class<?> clazz = service.getClass();
        final MethodInfo methodInfo = new MethodInfo(
                name, JSON, fieldInfos, ImmutableList.of(), // Ignore exceptions.
                ImmutableList.of(endpoint), HttpMethod.POST, null);
        methodInfos.computeIfAbsent(clazz, unused -> new HashSet<>()).add(methodInfo);
    }

    private static EndpointInfo endpointInfo(Route route, String hostnamePattern) {
        final List<String> paths = route.paths();
        return EndpointInfo.builder(hostnamePattern, RouteUtil.EXACT + paths.get(0))
                           .availableMimeTypes(availableMimeTypes(route))
                           .build();
    }

    private static Set<MediaType> availableMimeTypes(Route route) {
        final ImmutableSet.Builder<MediaType> builder = ImmutableSet.builder();
        final Set<MediaType> consumeTypes = route.consumes();
        builder.addAll(consumeTypes);
        if (!consumeTypes.contains(MediaType.GRAPHQL)) {
            builder.add(MediaType.GRAPHQL);
        }
        if (!consumeTypes.contains(MediaType.GRAPHQL_JSON)) {
            builder.add(MediaType.GRAPHQL_JSON);
        }
        return builder.build();
    }

    private static List<FieldInfo> fieldInfos() {
        return ImmutableList.of(
                FieldInfo.builder("query", STRING).requirement(FieldRequirement.REQUIRED).build(),
                FieldInfo.builder("operationName", STRING).requirement(FieldRequirement.OPTIONAL).build(),
                FieldInfo.builder("variables", MAP).requirement(FieldRequirement.OPTIONAL).build(),
                FieldInfo.builder("extensions", MAP).requirement(FieldRequirement.OPTIONAL).build()
        );
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

        return ServiceSpecification.generate(serviceInfos, unused -> null);
    }
}
