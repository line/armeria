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
package com.linecorp.armeria.internal.annotation;

import static com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.addToFirstIfExists;
import static com.linecorp.armeria.internal.annotation.AnnotationUtil.findDeclared;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.NoAnnotatedParameterException;
import com.linecorp.armeria.internal.annotation.AnnotatedValueResolver.RequestObjectResolver;
import com.linecorp.armeria.server.annotation.RequestConverter;

/**
 * A singleton class which manages factories for creating a bean. {@link #register(Class, Set, List)} should
 * be called at first to let {@link AnnotatedBeanFactoryRegistry} create a factory for a bean.
 */
final class AnnotatedBeanFactoryRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedBeanFactoryRegistry.class);

    // FIXME(trustin): Fix class loader leak. Should use Class as a weak key.
    private static final ConcurrentMap<BeanFactoryId, AnnotatedBeanFactory<?>> factories =
            new MapMaker().makeMap();
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
        if (!factories.containsKey(beanFactoryId)) {
            final AnnotatedBeanFactory<Object> factory = createFactory(beanFactoryId, objectResolvers);
            if (factory != null) {
                factories.put(beanFactoryId, factory);
                logger.debug("Registered a bean factory: {}", beanFactoryId);
            } else {
                factories.put(beanFactoryId, unsupportedBeanFactory);
            }
        }
        return beanFactoryId;
    }

    /**
     * Returns a factory of the specified {@link BeanFactoryId}.
     */
    static Optional<AnnotatedBeanFactory<?>> find(@Nullable BeanFactoryId beanFactoryId) {
        if (beanFactoryId == null) {
            return Optional.empty();
        }
        final AnnotatedBeanFactory<?> factory = factories.get(beanFactoryId);
        return factory != null && factory != unsupportedBeanFactory ? Optional.of(factory)
                                                                    : Optional.empty();
    }

    static Set<AnnotatedValueResolver> uniqueResolverSet() {
        return new TreeSet<>((o1, o2) -> {
            final String o1Name = o1.httpElementName();
            final String o2Name = o2.httpElementName();
            if (o1Name != null && o2Name != null && o1Name.equals(o2Name) &&
                o1.annotationType() == o2.annotationType()) {
                return 0;
            }
            // We are not ordering, but just finding duplicate elements.
            return -1;
        });
    }

    static void warnRedundantUse(AnnotatedValueResolver resolver, String genericString) {
        assert resolver.annotationType() != null;
        logger.warn("Found a redundant use of annotation in {}." +
                    " httpElementName: {}, annotation: {}", genericString,
                    resolver.httpElementName(), resolver.annotationType().getSimpleName());
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
                objectResolvers, findDeclared(beanFactoryId.type, RequestConverter.class));

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
                final List<RequestConverter> converters = findDeclared(constructor, RequestConverter.class);
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
            final List<RequestConverter> converters = findDeclared(method, RequestConverter.class);
            try {
                final List<AnnotatedValueResolver> resolvers =
                        AnnotatedValueResolver.ofBeanConstructorOrMethod(
                                method, beanFactoryId.pathParams,
                                addToFirstIfExists(objectResolvers, converters));
                if (!resolvers.isEmpty()) {
                    boolean redundant = false;
                    for (AnnotatedValueResolver resolver : resolvers) {
                        if (!uniques.add(resolver)) {
                            redundant = true;
                            warnRedundantUse(resolver, method.toGenericString());
                        }
                    }
                    if (redundant && resolvers.size() == 1) {
                        // Prevent redundant injection only when the size of parameter is 1.
                        // If the method contains more than 2 parameters and if one of them is used redundantly,
                        // we'd better to inject the method rather than ignore it.
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
            final List<RequestConverter> converters = findDeclared(field, RequestConverter.class);
            AnnotatedValueResolver.ofBeanField(field, beanFactoryId.pathParams,
                                               addToFirstIfExists(objectResolvers, converters))
                                  .ifPresent(resolver -> {
                                      if (!uniques.add(resolver)) {
                                          warnRedundantUse(resolver, field.toGenericString());
                                          return;
                                      }
                                      builder.put(field, resolver);
                                  });
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
}
