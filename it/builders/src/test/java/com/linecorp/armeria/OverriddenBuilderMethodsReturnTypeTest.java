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

package com.linecorp.armeria;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.reflections.adapters.JavaReflectionAdapter;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * Makes sure most builder overrides all overridden methods with the correct return type.
 */
class OverriddenBuilderMethodsReturnTypeTest {

    @Test
    void methodChaining() {
        final Set<String> excludedClasses = ImmutableSet.of("JsonLogFormatterBuilder",
                                                            "TextLogFormatterBuilder",
                                                            "PathStreamMessageBuilder",
                                                            "InputStreamStreamMessageBuilder",
                                                            "ContextPathAnnotatedServiceConfigSetters",
                                                            "ContextPathDecoratingBindingBuilder",
                                                            "ContextPathServiceBindingBuilder",
                                                            "ContextPathServicesBuilder",
                                                            "DecoratingServiceBindingBuilder",
                                                            "ServerBuilder",
                                                            "ServiceBindingBuilder",
                                                            "AnnotatedServiceBindingBuilder",
                                                            "VirtualHostAnnotatedServiceBindingBuilder",
                                                            "VirtualHostBuilder",
                                                            "VirtualHostContextPathDecoratingBindingBuilder",
                                                            "VirtualHostContextPathServiceBindingBuilder",
                                                            "VirtualHostContextPathServicesBuilder",
                                                            "VirtualHostDecoratingServiceBindingBuilder",
                                                            "VirtualHostServiceBindingBuilder",
                                                            "ChainedCorsPolicyBuilder",
                                                            "CorsPolicyBuilder",
                                                            "ConsulEndpointGroupBuilde",
                                                            "AbstractDnsResolverBuilder",
                                                            "AbstractRuleBuilder",
                                                            "AbstractRuleWithContentBuilder",
                                                            "DnsResolverGroupBuilder",
                                                            "AbstractCircuitBreakerMappingBuilder",
                                                            "CircuitBreakerMappingBuilder",
                                                            "CircuitBreakerRuleBuilder",
                                                            "CircuitBreakerRuleWithContentBuilder",
                                                            "AbstractDynamicEndpointGroupBuilder",
                                                            "DynamicEndpointGroupBuilder",
                                                            "DynamicEndpointGroupSetters",
                                                            "DnsAddressEndpointGroupBuilder",
                                                            "DnsEndpointGroupBuilder",
                                                            "DnsServiceEndpointGroupBuilder",
                                                            "DnsTextEndpointGroupBuilder",
                                                            "AbstractHealthCheckedEndpointGroupBuilder",
                                                            "HealthCheckedEndpointGroupBuilder",
                                                            "RetryRuleBuilder",
                                                            "RetryRuleWithContentBuilder",
                                                            "AbstractHeadersSanitizerBuilder",
                                                            "JsonHeadersSanitizerBuilder",
                                                            "TextHeadersSanitizerBuilder",
                                                            "EurekaEndpointGroupBuilder",
                                                            "KubernetesEndpointGroupBuilder",
                                                            "Resilience4jCircuitBreakerMappingBuilder",
                                                            "ZooKeeperEndpointGroupBuilder",
                                                            "AbstractCuratorFrameworkBuilder",
                                                            "ZooKeeperUpdatingListenerBuilder");
        final String packageName = "com.linecorp.armeria";
        findAllClasses(packageName).stream()
                                   .map(ReflectionUtils::forName)
                                   .filter(clazz -> clazz.getSimpleName().endsWith("Builder") &&
                                                    Modifier.isFinal(clazz.getModifiers()) &&
                                                    Modifier.isPublic(clazz.getModifiers()))
                                   .filter(clazz -> !excludedClasses.contains(clazz.getSimpleName()))
                                   .forEach(clazz -> {
                                       final List<Method> methods = overriddenMethods(clazz);
                                       for (Method m : methods) {
                                           assertThatNoException().isThrownBy(
                                                   () -> clazz.getDeclaredMethod(m.getName(),
                                                                                 m.getParameterTypes()));
                                           final Method overriddenMethod;
                                           try {
                                               overriddenMethod =
                                                       clazz.getDeclaredMethod(m.getName(),
                                                                               m.getParameterTypes());
                                           } catch (NoSuchMethodException e) {
                                               throw new RuntimeException(e);
                                           }
                                           assertThat(overriddenMethod.getReturnType())
                                                   .describedAs("Method name: " + m)
                                                   .isSameAs(clazz);
                                       }
                                   });
    }

    private static Collection<String> findAllClasses(String packageName) {
        final ConfigurationBuilder configuration = new ConfigurationBuilder()
                .filterInputsBy(filePath -> filePath != null && filePath.endsWith(".class"))
                .setUrls(ClasspathHelper.forPackage(packageName))
                .setScanners(new SubTypesScanner())
                .setMetadataAdapter(new JavaReflectionAdapter());
        final Reflections reflections = new Reflections(configuration);
        return reflections.getStore().get(SubTypesScanner.class.getSimpleName()).values();
    }

    private static List<Method> overriddenMethods(Class<?> clazz) {
        final Set<Class<?>> allSuperTypes = ReflectionUtils.getAllSuperTypes(clazz, input -> input != clazz);
        final ImmutableList<Method> methods = allSuperTypes.stream()
                                                           .flatMap(sc -> Arrays.stream(sc.getMethods()))
                                                           .distinct()
                                                           .collect(toImmutableList());
        // In general, if parent classes have a build method, did not override the method with that type.
        if (buildMethodExists(methods)) {
            return ImmutableList.of();
        } else {
            return methods.stream()
                          .filter(m -> m.getReturnType() == m.getDeclaringClass())
                          .collect(toImmutableList());
        }
    }

    private static boolean buildMethodExists(List<Method> methods) {
        return methods.stream().anyMatch(m -> "build".equals(m.getName()));
    }
}
