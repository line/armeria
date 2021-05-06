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
package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.addToFirstIfExists;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.getConstructors;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.NoAnnotatedParameterException;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.RequestObjectResolver;
import com.linecorp.armeria.server.annotation.RequestConverter;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * A singleton class which manages factories for creating a bean. {@link #register(Class, Set, List)} should
 * be called at first to let {@link AnnotatedBeanFactoryRegistry} create a factory for a bean.
 */
final class AnnotatedBeanFactoryRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedBeanFactoryRegistry.class);

    private static final ClassValue<AnnotatedBeanFactories> factories =
            new ClassValue<AnnotatedBeanFactories>() {
                @Override
                protected AnnotatedBeanFactories computeValue(Class<?> type) {
                    return new AnnotatedBeanFactories();
                }
            };

    private static final AnnotatedBeanFactory<?> unsupportedBeanFactory;

    static {
        final BeanFactoryId beanFactoryId = new BeanFactoryId(Object.class, ImmutableSet.of());
        final Entry<Constructor<Object>, List<AnnotatedValueResolver>> constructor =
                new SimpleImmutableEntry<>(null, ImmutableList.of());

        unsupportedBeanFactory = new AnnotatedBeanFactory<>(beanFactoryId, constructor,
                                                            ImmutableMap.of(), ImmutableMap.of());
    }

    /**
     * Returns a {@link BeanFactoryId} of the specified {@link Class} and {@code pathParams} for finding its
     * factory from the factory cache later.
     */
    static synchronized BeanFactoryId register(Class<?> clazz, Set<String> pathParams,
                                               List<RequestObjectResolver> objectResolvers) {
        final BeanFactoryId beanFactoryId = new BeanFactoryId(clazz, pathParams);
        final AnnotatedBeanFactories annotatedBeanFactories = factories.get(clazz);
        if (!annotatedBeanFactories.containsKey(beanFactoryId.pathParams)) {
            final AnnotatedBeanFactory<?> factory = createFactory(beanFactoryId, objectResolvers);
            if (factory != null) {
                annotatedBeanFactories.put(beanFactoryId.pathParams, factory);
                logger.debug("Registered a bean factory: {}", beanFactoryId);
            } else {
                annotatedBeanFactories.put(beanFactoryId.pathParams, unsupportedBeanFactory);
            }
        }
        return beanFactoryId;
    }

    /**
     * Returns a factory of the specified {@link BeanFactoryId}.
     */
    @Nullable
    static AnnotatedBeanFactory<?> find(@Nullable BeanFactoryId beanFactoryId) {
        if (beanFactoryId == null) {
            return null;
        }
        final AnnotatedBeanFactories annotatedBeanFactories = factories.get(beanFactoryId.type);
        final AnnotatedBeanFactory<?> factory = annotatedBeanFactories.get(beanFactoryId.pathParams);
        return factory != null && factory != unsupportedBeanFactory ? factory : null;
    }

    static Set<AnnotatedValueResolver> uniqueResolverSet() {
        return new TreeSet<>((o1, o2) -> {
            final String o1Name = o1.httpElementName();
            final String o2Name = o2.httpElementName();
            if (o1Name != null && o1Name.equals(o2Name) && o1.annotationType() == o2.annotationType()) {
                return 0;
            }
            // We are not ordering, but just finding duplicate elements.
            return -1;
        });
    }

    static void warnDuplicateResolver(AnnotatedValueResolver resolver, String genericString) {
        assert resolver.annotationType() != null;
        logger.warn("Ignoring a duplicate injection target '@{}(\"{}\")' at '{}'",
                    resolver.annotationType().getSimpleName(),
                    resolver.httpElementName(),
                    genericString);
    }

    @Nullable
    private static <T> AnnotatedBeanFactory<T> createFactory(BeanFactoryId beanFactoryId,
                                                             List<RequestObjectResolver> objectResolvers) {
        requireNonNull(beanFactoryId, "beanFactoryId");
        requireNonNull(objectResolvers, "objectResolvers");

        final int modifiers = beanFactoryId.type.getModifiers();
        if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers)) {
            // Only concrete classes can be handled.
            return null;
        }

        // Support request converters which are specified for a bean class. e.g.
        //
        // @RequestConverter(BeanConverterA.class)
        // @RequestConverter(BeanConverterB.class)
        // class CompositeBean {
        //     @RequestObject
        //     BeanA a;
        //     ...
        // }
        final List<RequestObjectResolver> resolvers = addToFirstIfExists(
                objectResolvers, AnnotationUtil.findDeclared(beanFactoryId.type, RequestConverter.class));

        final Entry<Constructor<T>, List<AnnotatedValueResolver>> constructor =
                findConstructor(beanFactoryId, resolvers);
        if (constructor == null) {
            // There is no constructor, so we cannot create a new instance.
            return null;
        }

        final List<AnnotatedValueResolver> constructorAnnotatedResolvers = constructor.getValue();

        // Find the methods whose parameters are not annotated with the same annotations in the constructor.
        // If there're parameters used redundantly, it would warn it.
        final Map<Method, List<AnnotatedValueResolver>> methods =
                findMethods(constructorAnnotatedResolvers, beanFactoryId, resolvers);

        // Find the fields which are not annotated with the same annotations in the constructor and methods.
        // If there're parameters used redundantly, it would warn it.
        final Map<Field, AnnotatedValueResolver> fields = findFields(constructorAnnotatedResolvers, methods,
                                                                     beanFactoryId, resolvers);

        if (constructor.getValue().isEmpty() && methods.isEmpty() && fields.isEmpty()) {
            // A default constructor exists but there is no annotated field or method.
            return null;
        }

        // Suppress Java language access checking.
        constructor.getKey().setAccessible(true);
        methods.keySet().forEach(method -> method.setAccessible(true));
        fields.keySet().forEach(field -> field.setAccessible(true));
        return new AnnotatedBeanFactory<>(beanFactoryId, constructor, methods, fields);
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
                final List<RequestConverter> converters =
                        AnnotationUtil.findDeclared(constructor, RequestConverter.class);
                final List<AnnotatedValueResolver> resolvers =
                        AnnotatedValueResolver.ofBeanConstructorOrMethod(
                                constructor, beanFactoryId.pathParams,
                                addToFirstIfExists(objectResolvers, converters));
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

    private static Map<Method, List<AnnotatedValueResolver>> findMethods(
            List<AnnotatedValueResolver> constructorAnnotatedResolvers,
            BeanFactoryId beanFactoryId, List<RequestObjectResolver> objectResolvers) {
        final Set<AnnotatedValueResolver> uniques = uniqueResolverSet();
        uniques.addAll(constructorAnnotatedResolvers);

        final Builder<Method, List<AnnotatedValueResolver>> methodsBuilder = ImmutableMap.builder();
        final Set<Method> methods = getAllMethods(beanFactoryId.type);
        for (final Method method : methods) {
            final List<RequestConverter> converters =
                    AnnotationUtil.findDeclared(method, RequestConverter.class);
            try {
                final List<AnnotatedValueResolver> resolvers =
                        AnnotatedValueResolver.ofBeanConstructorOrMethod(
                                method, beanFactoryId.pathParams,
                                addToFirstIfExists(objectResolvers, converters));
                if (!resolvers.isEmpty()) {
                    int redundant = 0;
                    for (AnnotatedValueResolver resolver : resolvers) {
                        if (!uniques.add(resolver)) {
                            redundant++;
                            warnDuplicateResolver(resolver, method.toGenericString());
                        }
                    }
                    if (redundant == resolvers.size()) {
                        // Prevent redundant injection only when all parameters are redundant.
                        // Otherwise, we'd better to inject the method rather than ignore it.
                        continue;
                    }
                    methodsBuilder.put(method, resolvers);
                }
            } catch (NoAnnotatedParameterException ignored) {
                // There's no annotated parameters in the method.
            }
        }
        return methodsBuilder.build();
    }

    private static Map<Field, AnnotatedValueResolver> findFields(
            List<AnnotatedValueResolver> constructorAnnotatedResolvers,
            Map<Method, List<AnnotatedValueResolver>> methods,
            BeanFactoryId beanFactoryId, List<RequestObjectResolver> objectResolvers) {
        final Set<AnnotatedValueResolver> uniques = uniqueResolverSet();
        uniques.addAll(constructorAnnotatedResolvers);
        methods.values().forEach(uniques::addAll);

        final Builder<Field, AnnotatedValueResolver> builder = ImmutableMap.builder();
        final Set<Field> fields = getAllFields(beanFactoryId.type);
        for (final Field field : fields) {
            final List<RequestConverter> converters =
                    AnnotationUtil.findDeclared(field, RequestConverter.class);
            final AnnotatedValueResolver resolver =
                    AnnotatedValueResolver.ofBeanField(field, beanFactoryId.pathParams,
                                                       addToFirstIfExists(objectResolvers, converters));
            if (resolver != null) {
                if (uniques.add(resolver)) {
                    builder.put(field, resolver);
                } else {
                    warnDuplicateResolver(resolver, field.toGenericString());
                }
            }
        }
        return builder.build();
    }

    private AnnotatedBeanFactoryRegistry() {}

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

        Class<?> type() {
            return type;
        }

        @Override
        public boolean equals(@Nullable Object o) {
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

    private static class AnnotatedBeanFactories {

        private final Map<Set<String>, AnnotatedBeanFactory<?>> factories = new Object2ObjectOpenHashMap<>();

        boolean containsKey(Set<String> pathParams) {
            return factories.containsKey(pathParams);
        }

        void put(Set<String> pathParams, AnnotatedBeanFactory<?> factory) {
            factories.put(pathParams, factory);
        }

        @Nullable
        AnnotatedBeanFactory<?> get(Set<String> pathParams) {
            return factories.get(pathParams);
        }
    }
}
