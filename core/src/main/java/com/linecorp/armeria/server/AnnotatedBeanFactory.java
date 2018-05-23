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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.getConstructors;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.server.AnnotatedValueResolver.NoAnnotatedParameterException;
import com.linecorp.armeria.server.AnnotatedValueResolver.RequestObjectResolver;
import com.linecorp.armeria.server.AnnotatedValueResolver.ResolverContext;

/**
 * A singleton class which manages factories for creating a bean. {@link #register(Class, Set, List)} should
 * be called at first to let {@link AnnotatedBeanFactory} create a factory for a bean.
 */
final class AnnotatedBeanFactory {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedBeanFactory.class);

    private static final Function<ResolverContext, ?> unsupportedBeanFactory = resolverContext -> null;
    private static final ConcurrentMap<BeanFactoryId,
            Function<ResolverContext, ?>> factories = new MapMaker().makeMap();

    /**
     * Returns a {@link BeanFactoryId} of the specified {@link Class} and {@code pathParams} for finding its
     * factory from the factory cache later.
     */
    static synchronized BeanFactoryId register(Class<?> clazz, Set<String> pathParams,
                                               List<RequestObjectResolver> objectResolvers) {
        final BeanFactoryId beanFactoryId = new BeanFactoryId(clazz, pathParams);
        if (!factories.containsKey(beanFactoryId)) {
            factories.put(beanFactoryId,
                          firstNonNull(createFactory(beanFactoryId, objectResolvers),
                                       unsupportedBeanFactory));
        }
        return beanFactoryId;
    }

    /**
     * Returns a factory of the specified {@link BeanFactoryId}.
     */
    static Optional<Function<ResolverContext, ?>> find(@Nullable BeanFactoryId beanFactoryId) {
        if (beanFactoryId == null) {
            return Optional.empty();
        }
        final Function<ResolverContext, ?> factory = factories.get(beanFactoryId);
        return factory != null && factory != unsupportedBeanFactory ? Optional.of(factory)
                                                                    : Optional.empty();
    }

    @Nullable
    private static <T> Function<ResolverContext, T> createFactory(BeanFactoryId beanFactoryId,
                                                                  List<RequestObjectResolver> objectResolvers) {
        requireNonNull(beanFactoryId, "beanFactoryId");
        requireNonNull(objectResolvers, "objectResolvers");

        final int modifiers = beanFactoryId.type.getModifiers();
        if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers)) {
            // Only concrete classes can be handled.
            return null;
        }

        final Entry<Constructor<T>, List<AnnotatedValueResolver>> constructor =
                findConstructor(beanFactoryId, objectResolvers);
        if (constructor == null) {
            // There is no constructor, so we cannot create a new instance.
            return null;
        }

        final List<Entry<Field, AnnotatedValueResolver>> fields = findFields(beanFactoryId, objectResolvers);
        final List<Entry<Method, List<AnnotatedValueResolver>>> methods = findMethods(beanFactoryId,
                                                                                      objectResolvers);

        if (constructor.getValue().isEmpty() && fields.isEmpty() && methods.isEmpty()) {
            // A default constructor exists but there is no annotated field or method.
            return null;
        }

        // Suppress Java language access checking.
        constructor.getKey().setAccessible(true);
        fields.forEach(field -> field.getKey().setAccessible(true));
        methods.forEach(method -> method.getKey().setAccessible(true));

        logger.debug("Registered a bean factory: {}", beanFactoryId);
        return resolverContext -> {
            try {
                final Object[] constructorArgs = AnnotatedValueResolver.toArguments(
                        constructor.getValue(), resolverContext);
                final T instance = constructor.getKey().newInstance(constructorArgs);

                for (final Entry<Field, AnnotatedValueResolver> field : fields) {
                    final Object fieldArg = field.getValue().resolve(resolverContext);
                    field.getKey().set(instance, fieldArg);
                }

                for (final Entry<Method, List<AnnotatedValueResolver>> method : methods) {
                    final Object[] methodArgs = AnnotatedValueResolver.toArguments(
                            method.getValue(), resolverContext);
                    method.getKey().invoke(instance, methodArgs);
                }

                return instance;
            } catch (Throwable cause) {
                throw new IllegalArgumentException(
                        "cannot instantiate a new object: " + beanFactoryId, cause);
            }
        };
    }

    @Nullable
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T> Entry<Constructor<T>, List<AnnotatedValueResolver>> findConstructor(
            BeanFactoryId beanFactoryId, List<RequestObjectResolver> objectResolvers) {

        Entry<Constructor<T>, List<AnnotatedValueResolver>> candidate = null;

        final Set<Constructor> constructors = getConstructors(beanFactoryId.type);
        for (final Constructor<T> constructor : constructors) {
            // A default constructor can be a candidate only if there has been no candidate yet.
            if (constructor.getParameterCount() == 0 && candidate == null) {
                candidate = new SimpleImmutableEntry<>(constructor, ImmutableList.of());
                continue;
            }

            try {
                final List<AnnotatedValueResolver> resolvers =
                        AnnotatedValueResolver.of(constructor, beanFactoryId.pathParams, objectResolvers);
                if (!resolvers.isEmpty()) {
                    // Can overwrite only if the current candidate is a default constructor.
                    if (candidate == null || candidate.getValue().isEmpty()) {
                        candidate = new SimpleImmutableEntry<>(constructor, resolvers);
                    } else {
                        throw new IllegalArgumentException(
                                "too many annotated constructors in " + beanFactoryId.type.getSimpleName() +
                                " (expected: 0 or 1)");
                    }
                }
            } catch (NoAnnotatedParameterException ignored) {
                // There's no annotated parameters in the constructor.
            }
        }
        return candidate;
    }

    private static List<Entry<Field, AnnotatedValueResolver>> findFields(
            BeanFactoryId beanFactoryId, List<RequestObjectResolver> objectResolvers) {
        final List<Entry<Field, AnnotatedValueResolver>> ret = new ArrayList<>();
        final Set<Field> fields = getAllFields(beanFactoryId.type);
        for (final Field field : fields) {
            AnnotatedValueResolver.of(field, beanFactoryId.pathParams, objectResolvers)
                                  .ifPresent(resolver -> ret.add(new SimpleImmutableEntry<>(field, resolver)));
        }
        return ret;
    }

    private static List<Entry<Method, List<AnnotatedValueResolver>>> findMethods(
            BeanFactoryId beanFactoryId, List<RequestObjectResolver> objectResolvers) {
        final List<Entry<Method, List<AnnotatedValueResolver>>> ret = new ArrayList<>();
        final Set<Method> methods = getAllMethods(beanFactoryId.type);
        for (final Method method : methods) {
            try {
                final List<AnnotatedValueResolver> resolvers =
                        AnnotatedValueResolver.of(method, beanFactoryId.pathParams, objectResolvers);
                if (!resolvers.isEmpty()) {
                    ret.add(new SimpleImmutableEntry<>(method, resolvers));
                }
            } catch (NoAnnotatedParameterException ignored) {
                // There's no annotated parameters in the method.
            }
        }
        return ret;
    }

    private AnnotatedBeanFactory() {}

    /**
     * An identifier of the registered bean factory.
     */
    static final class BeanFactoryId {
        private final Class<?> type;
        private final Set<String> pathParams;

        private BeanFactoryId(Class<?> type, Set<String> pathParams) {
            this.type = type;
            this.pathParams = ImmutableSortedSet.copyOf(pathParams);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != getClass()) {
                return false;
            }
            final BeanFactoryId that = (BeanFactoryId) o;
            return type == that.type &&
                   pathParams.equals(that.pathParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, pathParams);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("type", type.getName())
                              .add("pathParams", pathParams)
                              .toString();
        }
    }
}
