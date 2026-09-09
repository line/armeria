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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
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
        final List<DecoratorAndOrder> clazzDecorators =
                AnnotationUtil.getAllAnnotations(clazz).stream()
                              .flatMap(a -> collectDecorators(a).stream())
                              .collect(Collectors.toList());
        final List<DecoratorAndOrder> methodDecorators =
                AnnotationUtil.getAllAnnotations(method).stream()
                              .flatMap(a -> collectDecorators(a).stream())
                              .collect(Collectors.toList());

        final List<DecoratorAndOrder> decorators = new ArrayList<>();
        // Class-level decorators are applied before method-level decorators.
        decorators.addAll(clazzDecorators);
        decorators.addAll(methodDecorators);

        // Remove decorators with lower priority, keeping the original order intact.
        decorators.removeAll(getDecoratorsWithLowerPriority(decorators));

        // Sort decorators by "order" attribute values.
        decorators.sort(Comparator.comparing(DecoratorAndOrder::order));
        return decorators;
    }

    /**
     * Returns decorators which are annotated with {@link Decorator} and {@link Decorators} or user-defined.
     */
    private static List<DecoratorAndOrder> collectDecorators(Annotation annotation) {
        // Respect the order of decorators which is specified by a user. The first one is first applied
        // for most of the cases. But if @Decorator and user-defined decorators are specified in a mixed order,
        // the specified order and the applied order can be different. To overcome this problem, we introduce
        // "order" attribute to @Decorator annotation to sort decorators. If a user-defined decorator
        // annotation has "order" attribute, it will be also used for sorting.
        if (annotation instanceof Decorator) {
            final Decorator d = (Decorator) annotation;
            return ImmutableList.of(new DecoratorAndOrder(d, d, null, d.order()));
        }

        if (annotation instanceof Decorators) {
            final Decorator[] decorators = ((Decorators) annotation).value();

            final Builder<DecoratorAndOrder> builder = ImmutableList.builder();
            for (final Decorator d : decorators) {
                builder.add(new DecoratorAndOrder(d, d, null, d.order()));
            }
            return builder.build();
        }

        final Optional<DecoratorAndOrder> udd = userDefinedDecorator(annotation);
        if (udd.isPresent()) {
            return ImmutableList.of(udd.get());
        }

        final List<DecoratorAndOrder> udds = userDefinedDecorators(annotation);
        if (!udds.isEmpty()) {
            return udds;
        }

        return ImmutableList.of();
    }

    /**
     * Returns a decorator with its order if the specified {@code annotation} is one of the user-defined
     * decorator annotation.
     */
    private static Optional<DecoratorAndOrder> userDefinedDecorator(Annotation annotation) {
        // User-defined decorator MUST be annotated with @DecoratorFactory annotation.
        final DecoratorFactory df = AnnotationUtil.findFirstDeclared(annotation.annotationType(),
                                                                     DecoratorFactory.class);
        if (df == null) {
            return Optional.empty();
        }

        final int order = getOrder(annotation);
        return Optional.of(new DecoratorAndOrder(annotation, null, df, order));
    }

    /**
     * Returns user-defined decorators with their orders if the specified {@code annotation} has repeated
     * annotations which are one of the user-defined decorator annotation.
     */
    private static List<DecoratorAndOrder> userDefinedDecorators(Annotation annotation) {

        final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                            withName("value")), null);
        if (method == null) {
            return ImmutableList.of();
        }

        try {
            final Annotation[] decorators = (Annotation[]) method.invoke(annotation);
            return Arrays.stream(decorators)
                    .map(DecoratorAnnotationUtil::userDefinedDecorator)
                    .filter(Optional::isPresent).map(Optional::get)
                    .collect(Collectors.toList());
        } catch (Throwable ignore) {
            // The annotation may be a container of a decorator or may be not, so we just ignore
            // any exception from this clause.
        }
        return ImmutableList.of();
    }

    /**
     * Returns an order number which the specified {@code annotation} has.
    */
    private static int getOrder(Annotation annotation) {
        // If the annotation has "order" attribute, we can use it when sorting decorators.
        final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                            withName("order")), null);
        if (method == null) {
            return 0;
        }

        try {
            final Object value = method.invoke(annotation);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignore) {
            // A user-defined decorator may not have an 'order' attribute.
            // If it does not exist, '0' is used by default.
        }
        return 0;
    }

    /**
     * Returns decorators with lower priority. The priority is determined by comparing their order numbers
     * within annotations that have the same {@link DecoratorFactory}. All decorators, except the one with the
     * highest priority in each group, are returned.
     */
    private static List<DecoratorAndOrder> getDecoratorsWithLowerPriority(List<DecoratorAndOrder> decorators) {
        final Map<DecoratorFactory, List<DecoratorAndOrder>> decoratorsGroupedByDecoratorFactory =
                decorators.stream().filter(d -> d.decoratorFactory() != null)
                          .collect(Collectors.groupingBy(DecoratorAndOrder::decoratorFactory));

        final ImmutableList.Builder<DecoratorAndOrder> builder = ImmutableList.builder();
        for (Entry<DecoratorFactory, List<DecoratorAndOrder>> entry
                : decoratorsGroupedByDecoratorFactory.entrySet()) {

            final DecoratorFactory df = entry.getKey();
            final List<DecoratorAndOrder> groupedDecorators = entry.getValue();

            // If additivity is true, we don't need to compare priorities of decorators.
            if (df.additivity()) {
                continue;
            }

            // Collect decorators except the one with the highest priority.
            final Optional<Integer> minOrder = groupedDecorators.stream()
                                                    .min(Comparator.comparingInt(DecoratorAndOrder::order))
                                                    .map(DecoratorAndOrder::order);
            minOrder.ifPresent(order -> groupedDecorators.stream()
                                             .filter(decorator -> decorator.order() > order)
                                             .forEach(builder::add));
        }
        return builder.build();
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
