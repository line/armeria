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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.server.docs.DocServiceUtil.unifyFilter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;

class HttpDocServicePluginTest {

    private static final HttpDocServicePlugin plugin = new HttpDocServicePlugin();
    private static final String FOO_SERVICE_NAME = FooService.class.getName();

    @Test
    void testToTypeSignature() throws Exception {
        assertThat(HttpDocServicePlugin.HTTP_RESPONSE)
                .isEqualTo(TypeSignature.ofBase(HttpResponse.class.getSimpleName()));
    }

    @Test
    void matchMethodNames() {
        HttpDocServicePlugin.METHOD_NAMES.forEach((httpMethod, methodName) -> assertDoesNotThrow(() -> {
            final Method method = AbstractHttpService.class.getDeclaredMethod(methodName,
                                                                              ServiceRequestContext.class,
                                                                              HttpRequest.class);
            assertThat(method.getName()).isEqualTo(methodName);
            assertThat(method.getReturnType()).isEqualTo(HttpResponse.class);
        }));
    }

    @Test
    void testGenerateSpecification() {
        final FooService fooService = new FooService();
        final Map<String, ServiceInfo> services = services((plugin, service, method) -> true,
                                                           (plugin, service, method) -> false,
                                                           "/",
                                                           fooService);
        assertThat(services).containsOnlyKeys(FOO_SERVICE_NAME);
        checkFooService(services.get(FOO_SERVICE_NAME));
    }

    @Test
    void include() {

        // 1. Nothing specified: include all methods.
        // 2. Exclude specified: include all methods except the methods which the exclude filter returns true.
        // 3. Include specified: include the methods which the include filter returns true.
        // 4. Include and exclude specified: include the methods which the include filter returns true and
        //    the exclude filter returns false.

        final FooService fooService = new FooService();

        // 1. Nothing specified.
        DocServiceFilter include = (plugin, service, method) -> true;
        DocServiceFilter exclude = (plugin, service, method) -> false;
        Map<String, ServiceInfo> services = services(include, exclude, "/", fooService);
        assertThat(services).containsOnlyKeys(FOO_SERVICE_NAME);

        // 2. Exclude specified.
        exclude = DocServiceFilter.ofMethodName(FOO_SERVICE_NAME, "doGet");
        services = services(include, exclude, "/", fooService);
        assertThat(services).containsOnlyKeys(FOO_SERVICE_NAME);

        List<String> methods = methods(services);
        assertThat(methods).doesNotContain("doGet");

        // 3-1. Include serviceName specified.
        include = DocServiceFilter.ofServiceName(FOO_SERVICE_NAME);
        // Set the exclude to the default.
        exclude = (plugin, service, method) -> false;
        services = services(include, exclude, "/", fooService);
        assertThat(services).containsOnlyKeys(FOO_SERVICE_NAME);

        methods = methods(services);
        assertThat(methods).containsAll(HttpDocServicePlugin.METHOD_NAMES.values());

        // 3-2. Include methodName specified.
        include = DocServiceFilter.ofMethodName(FOO_SERVICE_NAME, "doGet");
        services = services(include, exclude, "/", fooService);
        assertThat(services).containsOnlyKeys(FOO_SERVICE_NAME);

        methods = methods(services);
        assertThat(methods).containsOnlyOnce("doGet");

        // 4-1. Include and exclude specified.
        include = DocServiceFilter.ofServiceName(FOO_SERVICE_NAME);
        exclude = DocServiceFilter.ofMethodName(FOO_SERVICE_NAME, "doGet");
        services = services(include, exclude, "/", fooService);
        assertThat(services).containsOnlyKeys(FOO_SERVICE_NAME);

        methods = methods(services);
        assertThat(methods).doesNotContain("doGet");

        // 4-2. Include and exclude specified.
        include = DocServiceFilter.ofMethodName(FOO_SERVICE_NAME, "doGet");
        exclude = DocServiceFilter.ofServiceName(FOO_SERVICE_NAME);
        services = services(include, exclude, "/", fooService);
        assertThat(services.size()).isZero();
    }

    private static Map<String, ServiceInfo> services(DocServiceFilter include,
                                                     DocServiceFilter exclude,
                                                     String pathPattern,
                                                     AbstractHttpService service) {
        final Server server = Server.builder()
                                    .service(pathPattern, service)
                                    .build();
        final ServiceSpecification specification =
                plugin.generateSpecification(ImmutableSet.copyOf(server.serviceConfigs()),
                                             unifyFilter(include, exclude));
        return specification.services()
                            .stream()
                            .collect(toImmutableMap(ServiceInfo::name, Function.identity()));
    }

    private static void checkFooService(ServiceInfo fooServiceInfo) {
        assertThat(fooServiceInfo.exampleHeaders()).isEmpty();
        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).containsOnlyKeys(HttpDocServicePlugin.METHOD_NAMES.values());

        final MethodInfo doGet = methods.get("doGet");
        assertThat(doGet.exampleHeaders()).isEmpty();
        assertThat(doGet.exampleRequests()).isEmpty();

        assertThat(doGet.parameters()).isEmpty();

        assertThat(doGet.returnTypeSignature()).isEqualTo(HttpDocServicePlugin.HTTP_RESPONSE);

        assertThat(doGet.endpoints()).containsExactly(EndpointInfo.builder("*", "exact:/")
                                                                      .defaultMimeType(MediaType.JSON)
                                                                      .build());
    }

    private static List<String> methods(Map<String, ServiceInfo> services) {
        return services.get(FOO_SERVICE_NAME).methods()
                       .stream()
                       .map(MethodInfo::name)
                       .collect(toImmutableList());
    }

    private static class FooService extends AbstractHttpService {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
