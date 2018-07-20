/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.server.AnnotatedHttpDocServicePlugin.INT32;
import static com.linecorp.armeria.server.AnnotatedHttpDocServicePlugin.INT64;
import static com.linecorp.armeria.server.AnnotatedHttpDocServicePlugin.STRING;
import static com.linecorp.armeria.server.AnnotatedHttpDocServicePlugin.VOID;
import static com.linecorp.armeria.server.AnnotatedHttpDocServicePlugin.newExceptionInfo;
import static com.linecorp.armeria.server.AnnotatedHttpDocServicePlugin.toTypeSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeSet;
import com.linecorp.armeria.server.AnnotatedHttpServiceFactory.AnnotatedHttpServiceElement;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.ExceptionInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.MethodInfo;
import com.linecorp.armeria.server.docs.ServiceInfo;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;

public class AnnotatedHttpDocServicePluginTest {

    private final AnnotatedHttpDocServicePlugin plugin = new AnnotatedHttpDocServicePlugin();

    @Test
    public void testToTypeSignature() throws Exception {
        assertThat(toTypeSignature(Void.class)).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(toTypeSignature(void.class)).isEqualTo(TypeSignature.ofBase("void"));
        assertThat(toTypeSignature(Boolean.class)).isEqualTo(TypeSignature.ofBase("boolean"));
        assertThat(toTypeSignature(boolean.class)).isEqualTo(TypeSignature.ofBase("boolean"));
        assertThat(toTypeSignature(Byte.class)).isEqualTo(TypeSignature.ofBase("int8"));
        assertThat(toTypeSignature(byte.class)).isEqualTo(TypeSignature.ofBase("int8"));
        assertThat(toTypeSignature(Short.class)).isEqualTo(TypeSignature.ofBase("int16"));
        assertThat(toTypeSignature(short.class)).isEqualTo(TypeSignature.ofBase("int16"));
        assertThat(toTypeSignature(Integer.class)).isEqualTo(TypeSignature.ofBase("int32"));
        assertThat(toTypeSignature(int.class)).isEqualTo(TypeSignature.ofBase("int32"));
        assertThat(toTypeSignature(Long.class)).isEqualTo(TypeSignature.ofBase("int64"));
        assertThat(toTypeSignature(long.class)).isEqualTo(TypeSignature.ofBase("int64"));
        assertThat(toTypeSignature(Float.class)).isEqualTo(TypeSignature.ofBase("float"));
        assertThat(toTypeSignature(float.class)).isEqualTo(TypeSignature.ofBase("float"));
        assertThat(toTypeSignature(Double.class)).isEqualTo(TypeSignature.ofBase("double"));
        assertThat(toTypeSignature(double.class)).isEqualTo(TypeSignature.ofBase("double"));
        assertThat(toTypeSignature(String.class)).isEqualTo(TypeSignature.ofBase("string"));

        assertThat(toTypeSignature(Byte[].class)).isEqualTo(TypeSignature.ofBase("binary"));
        assertThat(toTypeSignature(byte[].class)).isEqualTo(TypeSignature.ofBase("binary"));

        // Array is not supported.
        assertThatThrownBy(() -> toTypeSignature(int[].class))
                .isExactlyInstanceOf(IllegalArgumentException.class);

        // Container types.

        final TypeSignature list = toTypeSignature(FieldContainer.class.getDeclaredField("list")
                                                                       .getGenericType());
        assertThat(list).isEqualTo(TypeSignature.ofList(TypeSignature.ofBase("string")));

        final TypeSignature set = toTypeSignature(FieldContainer.class.getDeclaredField("set")
                                                                      .getGenericType());
        assertThat(set).isEqualTo(TypeSignature.ofSet(TypeSignature.ofBase("float")));

        final TypeSignature future = toTypeSignature(FieldContainer.class.getDeclaredField("future")
                                                                         .getGenericType());
        assertThat(future).isEqualTo(TypeSignature.ofContainer("CompletableFuture",
                                                               TypeSignature.ofBase("double")));

        // Other than above, every type is named type signature.
        assertThat(toTypeSignature(FieldContainer.class).name())
                .isEqualTo("com.linecorp.armeria.server.AnnotatedHttpDocServicePluginTest$FieldContainer");
    }

    @Test
    public void testNewExceptionInfo() {
        final ExceptionInfo exception = newExceptionInfo(FooException.class);
        assertThat(exception).isEqualTo(new ExceptionInfo(FooException.class.getName(), ImmutableList.of(
                new FieldInfo("fooException1", FieldRequirement.DEFAULT, STRING),
                new FieldInfo("fooException2", FieldRequirement.DEFAULT, INT32))));
    }

    @Test
    public void testGenerateSpecification() {
        final ServiceSpecification specification = plugin.generateSpecification(
                ImmutableSet.copyOf(serviceConfigs()));

        // Ensure the specification contains all services.
        final Map<String, ServiceInfo> services =
                specification.services().stream()
                             .collect(toImmutableMap(ServiceInfo::name, Function.identity()));

        assertThat(services).containsOnlyKeys(FooClass.class.getName(), BarClass.class.getName());

        final ServiceInfo fooServiceInfo = services.get(FooClass.class.getName());
        assertThat(fooServiceInfo.exampleHttpHeaders()).isEmpty();

        final Map<String, MethodInfo> methods =
                fooServiceInfo.methods().stream()
                              .collect(toImmutableMap(MethodInfo::name, Function.identity()));
        assertThat(methods).containsKeys("fooMethod", "foo2Method");

        final MethodInfo fooMethod = methods.get("fooMethod");
        assertThat(fooMethod.exampleHttpHeaders()).isEmpty();
        assertThat(fooMethod.exampleRequests()).isEmpty();

        assertThat(fooMethod.parameters()).hasSize(2);
        assertThat(fooMethod.parameters()).containsExactlyInAnyOrder(
                new FieldInfo("foo", FieldRequirement.REQUIRED, STRING),
                new FieldInfo("foo1", FieldRequirement.REQUIRED, INT64));

        assertThat(fooMethod.returnTypeSignature()).isEqualTo(VOID);

        assertThat(fooMethod.exceptionTypeSignatures()).hasSize(1);
        assertThat(Iterables.get(fooMethod.exceptionTypeSignatures(), 0)).isEqualTo(
                TypeSignature.ofNamed(FooException.class));

        assertThat(fooMethod.endpoints()).containsExactly(new EndpointInfo("*", "/foo", "",
                                                                           MediaType.JSON,
                                                                           ImmutableSet.of(MediaType.JSON)));
        final ServiceInfo barServiceInfo = services.get(BarClass.class.getName());
    }

    private static List<ServiceConfig> serviceConfigs() {
        final List<AnnotatedHttpServiceElement> fooElements = AnnotatedHttpServiceFactory.find(
                "/", new FooClass(), ImmutableList.of());
        final List<AnnotatedHttpServiceElement> barElements = AnnotatedHttpServiceFactory.find(
                "/", new BarClass(), ImmutableList.of());
        final Builder<ServiceConfig> builder = ImmutableSet.builder();
        fooElements.forEach(element -> builder.add(new ServiceConfig(element.pathMapping(),
                                                                     element.service(), null)));
        barElements.forEach(element -> builder.add(new ServiceConfig(element.pathMapping(),
                                                                     element.service(), null)));
        Set<ServiceConfig> serviceConfigs = builder.build();
        return new VirtualHost("hostname", "*", null, serviceConfigs,
                               new MediaTypeSet(ImmutableList.of(MediaType.JSON))).serviceConfigs();
    }

    private static class FieldContainer {
        @Nullable
        List<String> list;
        @Nullable
        Set<Float> set;
        @Nullable
        CompletableFuture<Double> future;
    }

    private static class FooClass {

        @Get("/foo")
        public void fooMethod(@Param("foo") String foo, @Header("foo1") long foo1) throws FooException {}

        @Get("/foo2")
        public long foo2Method(@Param("foo2") String foo2) {
            return 0;
        }
    }

    private static class BarClass {
        @Get("/bar")
        public void barMethod(@Param("bar") String bar) {}
    }

    private static class FooException extends RuntimeException {
        private static final long serialVersionUID = 1940943028766995053L;

        @Nullable
        String fooException1;
        int fooException2;
    }
}
