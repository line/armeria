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

package com.linecorp.armeria.internal.server.annotation;

import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withName;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;

/**
 * A utility class for {@link Decorator}.
 */
public final class DecoratorAnnotationUtil {

    /**
     * Returns a decorator list which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    public static List<DecoratorAndOrder> collectDecorators(Class<?> clazz, Method method) {
        final List<DecoratorAndOrder> decorators = new ArrayList<>();

        // Class-level decorators are applied before method-level decorators.
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(clazz));
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(method));

        // Sort decorators by "order" attribute values.
        decorators.sort(Comparator.comparing(DecoratorAndOrder::order));
        return decorators;
    }

    /**
     * Adds decorators to the specified {@code list}. Decorators which are annotated with {@link Decorator}
     * and user-defined decorators will be collected.
     */
    private static void collectDecorators(List<DecoratorAndOrder> list, List<Annotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }

        // Respect the order of decorators which is specified by a user. The first one is first applied
        // for most of the cases. But if @Decorator and user-defined decorators are specified in a mixed order,
        // the specified order and the applied order can be different. To overcome this problem, we introduce
        // "order" attribute to @Decorator annotation to sort decorators. If a user-defined decorator
        // annotation has "order" attribute, it will be also used for sorting.
        for (final Annotation annotation : annotations) {
            if (annotation instanceof Decorator) {
                final Decorator d = (Decorator) annotation;
                list.add(new DecoratorAndOrder(d, d, null, d.order()));
                continue;
            }

            if (annotation instanceof Decorators) {
                final Decorator[] decorators = ((Decorators) annotation).value();
                for (final Decorator d : decorators) {
                    list.add(new DecoratorAndOrder(d, d, null, d.order()));
                }
                continue;
            }

            DecoratorAndOrder udd = userDefinedDecorator(annotation);
            if (udd != null) {
                list.add(udd);
                continue;
            }

            // If user-defined decorators are repeatable and they are specified more than once.
            try {
                final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                    withName("value")), null);
                if (method != null) {
                    final Annotation[] decorators = (Annotation[]) method.invoke(annotation);
                    for (final Annotation decorator : decorators) {
                        udd = userDefinedDecorator(decorator);
                        if (udd == null) {
                            break;
                        }
                        list.add(udd);
                    }
                }
            } catch (Throwable ignore) {
                // The annotation may be a container of a decorator or may be not, so we just ignore
                // any exception from this clause.
            }
        }
    }

    /**
     * Returns a decorator with its order if the specified {@code annotation} is one of the user-defined
     * decorator annotation.
     */
    @Nullable
    private static DecoratorAndOrder userDefinedDecorator(Annotation annotation) {
        // User-defined decorator MUST be annotated with @DecoratorFactory annotation.
        final DecoratorFactory df = AnnotationUtil.findFirstDeclared(annotation.annotationType(),
                                                                     DecoratorFactory.class);
        if (df == null) {
            return null;
        }

        // If the annotation has "order" attribute, we can use it when sorting decorators.
        int order = 0;
        try {
            final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                withName("order")), null);
            if (method != null) {
                final Object value = method.invoke(annotation);
                if (value instanceof Integer) {
                    order = (Integer) value;
                }
            }
        } catch (Throwable ignore) {
            // A user-defined decorator may not have an 'order' attribute.
            // If it does not exist, '0' is used by default.
        }
        return new DecoratorAndOrder(annotation, null, df, order);
    }

    private DecoratorAnnotationUtil() {}

    /**
     * An internal class to hold a decorator with its order.
     */
    public static final class DecoratorAndOrder {
        private final Annotation annotation;
        @Nullable
        private final Decorator decoratorAnnotation;
        @Nullable
        private final DecoratorFactory decoratorFactory;
        private final int order;

        private DecoratorAndOrder(Annotation annotation, @Nullable Decorator decoratorAnnotation,
                                  @Nullable DecoratorFactory decoratorFactory, int order) {
            this.annotation = annotation;
            this.decoratorAnnotation = decoratorAnnotation;
            this.decoratorFactory = decoratorFactory;
            this.order = order;
        }

        @VisibleForTesting
        public Annotation annotation() {
            return annotation;
        }

        @Nullable
        @VisibleForTesting
        public Decorator decoratorAnnotation() {
            return decoratorAnnotation;
        }

        @Nullable
        @VisibleForTesting
        public DecoratorFactory decoratorFactory() {
            return decoratorFactory;
        }

        public Function<? super HttpService, ? extends HttpService> decorator(
                DependencyInjector dependencyInjector) {
            if (decoratorFactory != null) {
                @SuppressWarnings("unchecked")
                final DecoratorFactoryFunction<Annotation> factory = AnnotatedObjectFactory
                        .getInstance(decoratorFactory, DecoratorFactoryFunction.class, dependencyInjector);
                return factory.newDecorator(annotation);
            }
            assert decoratorAnnotation != null;
            return service -> service.decorate(AnnotatedObjectFactory.getInstance(
                    decoratorAnnotation, DecoratingHttpServiceFunction.class, dependencyInjector));
        }

        public int order() {
            return order;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("annotation", annotation)
                              .add("decoratorAnnotation", decoratorAnnotation)
                              .add("decoratorFactory", decoratorFactory)
                              .add("order", order)
                              .toString();
        }
    }
}
