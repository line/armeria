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

package com.linecorp.armeria.internal.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.thrift.THttpService;

import testing.thrift.full.camel.TestService;
import testing.thrift.full.camel.TestService.AsyncIface;

class ThriftDocServicePluginTest {
    private static final String SERVICE_NAME = TestService.class.getName();
    private static final ThriftDocServicePlugin GENERATOR = new ThriftDocServicePlugin();
    private static final ThriftDescriptiveTypeInfoProvider DESCRIPTIVE_TYPE_INFO_PROVIDER =
            new ThriftDescriptiveTypeInfoProvider();

    @Test
    void servicesTest() {
        final Map<String, ServiceInfo> services = services((plugin, service, method) -> true,
                                                           (plugin, service, method) -> false);
        assertThat(services).containsKey(SERVICE_NAME);
        final ServiceInfo serviceInfo = services.get(SERVICE_NAME);
        final Map<String, MethodInfo> methods =
                serviceInfo.methods().stream()
                           .collect(toImmutableMap(MethodInfo::name, Function.identity()));

        assertThat(methods).containsOnlyKeys("say_hello", "SayHelloNow", "sayHelloWorld");
    }

    private static Map<String, ServiceInfo> services(DocServiceFilter include, DocServiceFilter exclude) {
        final Server server =
                Server.builder()
                      .service("/",
                               THttpService.of(mock(AsyncIface.class)))
                      .build();

        // Generate the specification with the ServiceConfigs.
        final ServiceSpecification specification = GENERATOR.generateSpecification(
                ImmutableSet.copyOf(server.serviceConfigs()),
                unifyFilter(include, exclude), DESCRIPTIVE_TYPE_INFO_PROVIDER);

        // Ensure the specification contains all services.
        return specification.services()
                            .stream()
                            .collect(toImmutableMap(ServiceInfo::name, Function.identity()));
    }
}
