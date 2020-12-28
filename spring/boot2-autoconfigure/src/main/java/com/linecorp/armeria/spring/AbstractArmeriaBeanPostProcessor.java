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
package com.linecorp.armeria.spring;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.beans.PropertyDescriptor;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.beans.factory.annotation.InjectionMetadata.InjectedElement;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ReflectionUtils;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;

/**
 * Abstract class for implementing ArmeriaBeanPostProcessor of boot2-autoconfigure module
 * and ArmeriaSpringBoot1BeanPostProcessor of boot1-autoconfigure module.
 */
abstract class AbstractArmeriaBeanPostProcessor {

    private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);

    private final Map<SessionProtocol, Integer> portCache =
            new ConcurrentHashMap<>(SessionProtocol.values().length);

    private final List<Integer> portsCache = new CopyOnWriteArrayList<>();

    private final BeanFactory beanFactory;

    @Nullable
    private Server server;

    AbstractArmeriaBeanPostProcessor(BeanFactory beanFactory) {
        this.beanFactory = requireNonNull(beanFactory, "beanFactory");
    }

    protected InjectionMetadata findLocalArmeriaPortMetadata(
            String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
        final String cacheKey = Strings.isNullOrEmpty(beanName) ? beanName : clazz.getName();
        InjectionMetadata metadata = injectionMetadataCache.get(cacheKey);
        if (InjectionMetadata.needsRefresh(metadata, clazz)) {
            synchronized (injectionMetadataCache) {
                metadata = injectionMetadataCache.get(cacheKey);
                if (InjectionMetadata.needsRefresh(metadata, clazz)) {
                    if (metadata != null) {
                        metadata.clear(pvs);
                    }
                    metadata = buildLocalArmeriaPortMetadata(clazz);
                    injectionMetadataCache.put(cacheKey, metadata);
                }
            }
        }
        return metadata;
    }

    private InjectionMetadata buildLocalArmeriaPortMetadata(Class<?> clazz) {
        final List<InjectedElement> elements = new ArrayList<>();
        Class<?> targetClass = clazz;

        do {
            final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();

            ReflectionUtils.doWithLocalFields(targetClass, field -> {
                if (field.isAnnotationPresent(LocalArmeriaPort.class)) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        throw new IllegalStateException(
                                "LocalArmeriaPort annotation is not supported on a static field: " +
                                field.getName());
                    }
                    currElements.add(new LocalArmeriaPortElement(field, field, null));
                } else if (field.isAnnotationPresent(LocalArmeriaPorts.class)) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        throw new IllegalStateException(
                                "LocalArmeriaPorts annotation is not supported on a static field: " +
                                field.getName());
                    }
                    currElements.add(new LocalArmeriaPortsElement(field, null));
                }
            });

            ReflectionUtils.doWithLocalMethods(targetClass, method -> {
                final Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
                if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
                    return;
                }
                if (bridgedMethod.isAnnotationPresent(LocalArmeriaPort.class)) {
                    if (Modifier.isStatic(method.getModifiers())) {
                        throw new IllegalStateException(
                                "LocalArmeriaPort annotation is not supported on a static method: " +
                                method.getName());
                    }
                    final PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
                    currElements.add(new LocalArmeriaPortElement(method, bridgedMethod, pd));
                } else if (bridgedMethod.isAnnotationPresent(LocalArmeriaPorts.class)) {
                    if (Modifier.isStatic(method.getModifiers())) {
                        throw new IllegalStateException(
                                "LocalArmeriaPorts annotation is not supported on a static method: " +
                                method.getName());
                    }
                    final PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
                    currElements.add(new LocalArmeriaPortsElement(method, pd));
                }
            });

            elements.addAll(0, currElements);
            targetClass = targetClass.getSuperclass();
        }
        while (targetClass != null && targetClass != Object.class);

        return new InjectionMetadata(clazz, elements);
    }

    private void setServer(Server server) {
        this.server = requireNonNull(server, "server");
    }

    @Nullable
    private Server getServer() {
        return server;
    }

    /**
     * Class representing injection information about an annotated field
     * or setter method, supporting the {@link LocalArmeriaPort}.
     */
    private final class LocalArmeriaPortElement extends InjectionMetadata.InjectedElement {

        private final int port;

        private LocalArmeriaPortElement(Member member, AnnotatedElement ae, @Nullable PropertyDescriptor pd) {
            super(member, pd);
            final LocalArmeriaPort localArmeriaPort = ae.getAnnotation(LocalArmeriaPort.class);
            final SessionProtocol protocol = localArmeriaPort.value();
            Server server = getServer();
            if (server == null) {
                server = beanFactory.getBean(Server.class);
                setServer(server);
            }

            Integer port = portCache.get(protocol);
            if (port == null) {
                port = server.activeLocalPort(protocol);
                portCache.put(protocol, port);
            }
            this.port = port;
        }

        /**
         * Resolve the object against the application context.
         */
        @Override
        protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
            return port;
        }
    }

    /**
     * Class representing injection information about an annotated field
     * or setter method, supporting the {@link LocalArmeriaPorts}.
     */
    private final class LocalArmeriaPortsElement extends InjectionMetadata.InjectedElement {

        private final List<Integer> ports;

        private LocalArmeriaPortsElement(Member member, @Nullable PropertyDescriptor pd) {
            super(member, pd);
            Server server = getServer();
            if (server == null) {
                server = beanFactory.getBean(Server.class);
                setServer(server);
            }

            final Builder<Integer> ports = ImmutableList.builder();
            if (portsCache.isEmpty()) {
                synchronized (portsCache) {
                    if (portsCache.isEmpty()) {
                        ports.addAll(server.activePorts().values().stream()
                                           .map(p -> p.localAddress().getPort())
                                           .collect(toImmutableList()));
                        portsCache.addAll(ports.build());
                    } else {
                        ports.addAll(portsCache);
                    }
                }
            } else {
                ports.addAll(portsCache);
            }
            this.ports = ports.build();
        }

        /**
         * Resolve the object against the application context.
         */
        @Override
        protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
            return ports;
        }
    }
}
