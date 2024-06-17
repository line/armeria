/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.reflections.ReflectionUtils;

import com.google.common.collect.ImmutableSet;

class VirtualHostAndServiceConfigConsistencyTest {

    @Test
    void testApiConsistencyBetweenVirtualHostAndServiceConfig() {
        // Check method consistency between VirtualHost and ServiceConfig excluding certain methods.
        final Set<String> virtualHostMethods =
                ReflectionUtils.getMethods(VirtualHost.class, m -> Modifier.isPublic(m.getModifiers()))
                               .stream()
                               .map(Method::getName)
                               .collect(Collectors.toSet());

        final Set<String> ignorableVirtualHostMethods = ImmutableSet.of(
                "defaultHostname",
                "sslContext",
                "tlsEngineType",
                "accessLogger",
                "port",
                "hostnamePattern",
                "findServiceConfig",
                "serviceConfigs"
        );
        virtualHostMethods.removeAll(ignorableVirtualHostMethods);

        final Set<String> serviceConfigMethods =
                ReflectionUtils.getMethods(ServiceConfig.class, m -> Modifier.isPublic(m.getModifiers()))
                               .stream()
                               .map(Method::getName)
                               .collect(Collectors.toSet());

        final Set<String> ignorableServiceConfigMethods = ImmutableSet.of(
                "defaultServiceName",
                "mappedRoute",
                "virtualHost",
                "route",
                "service",
                "defaultHeaders",
                "transientServiceOptions",
                "contextHook"
        );
        serviceConfigMethods.removeAll(ignorableServiceConfigMethods);

        assertThat(virtualHostMethods).hasSameElementsAs(serviceConfigMethods);
    }
}
