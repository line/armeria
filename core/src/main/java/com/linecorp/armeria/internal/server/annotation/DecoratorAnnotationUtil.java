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
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.DependencyInjector;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;

/**
 * A utility class for {@link Decorator}.
 */
public final class DecoratorAnnotationUtil {

    public static boolean hasDecorator(AnnotatedElement element) {
        final List<Annotation> annotations = AnnotationUtil.getAllAnnotations(element);
        if (annotations.isEmpty()) {
            return false;
        }
        for (final Annotation annotation : annotations) {
            if (annotation instanceof Decorator || annotation instanceof Decorators) {
                return true;
            }

            DecoratorFactory df = decoratorFactory(annotation);
            if (df != null) {
                return true;
            }

            final Annotation[] annotationsFromValue = maybeCollectAnnotationsFromValue(annotation);
            if (annotationsFromValue != null) {
                for (final Annotation decorator : annotationsFromValue) {
                    df = decoratorFactory(decorator);
                    if (df != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private static Annotation[] maybeCollectAnnotationsFromValue(Annotation annotation) {
        // e.g.
        // @interface UserDefinedRepeatableDecorators {
        //     UserDefinedRepeatableDecorator[] value();
        // }

        final List<DecoratorAndOrder> decoratorAndOrders = new ArrayList<>();
        // If user-defined decorators are repeatable and they are specified more than once.
        try {
            final Method method = Iterables.getFirst(getMethods(annotation.annotationType(),
                                                                withName("value")), null);
            if (method == null) {
                return null;
            }
            return (Annotation[]) method.invoke(annotation);
        } catch (Throwable ignore) {
            // The annotation may be a container of a decorator or may be not, so we just ignore
            // any exceptions from this clause.
            return null;
        }
    }

    /**
     * Returns a decorator list which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    public static List<DecoratorAndOrder> collectDecorators(Class<?> clazz, Method method,
                                                            List<DependencyInjector> dependencyInjectors) {
        final List<DecoratorAndOrder> decorators = new ArrayList<>();

        // Class-level decorators are applied before method-level decorators.
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(clazz), dependencyInjectors);
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(method), dependencyInjectors);

        // Sort decorators by "order" attribute values.
        decorators.sort(Comparator.comparing(DecoratorAndOrder::order));
        return decorators;
    }

    /**
     * Adds decorators to the specified {@code list}. Decorators which are annotated with {@link Decorator}
     * and user-defined decorators will be collected.
     */
    private static void collectDecorators(List<DecoratorAndOrder> list, List<Annotation> annotations,
                                          List<DependencyInjector> dependencyInjectors) {
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
                list.add(new DecoratorAndOrder(d, newDecorator(d, dependencyInjectors), d.order()));
                continue;
            }

            if (annotation instanceof Decorators) {
                final Decorator[] decorators = ((Decorators) annotation).value();
                for (final Decorator d : decorators) {
                    list.add(new DecoratorAndOrder(d, newDecorator(d, dependencyInjectors), d.order()));
                }
                continue;
            }

            DecoratorAndOrder udd = userDefinedDecorator(annotation, dependencyInjectors);
            if (udd != null) {
                list.add(udd);
                continue;
            }

            final Annotation[] annotationsFromValue = maybeCollectAnnotationsFromValue(annotation);
            if (annotationsFromValue != null) {
                for (final Annotation decorator : annotationsFromValue) {
                    udd = userDefinedDecorator(decorator, dependencyInjectors);
                    if (udd != null) {
                        list.add(udd);
                    }
                }
            }
        }
    }

    /**
     * Returns a {@link HttpService} which is specified by {@link Decorator} annotations and user-defined
     * decorator annotations.
     */
    public static HttpService applyDecorators(List<DecoratorAndOrder> decorators,
                                              HttpService delegate) {
        Function<? super HttpService, ? extends HttpService> decorator = Function.identity();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            final DecoratorAndOrder d = decorators.get(i);
            decorator = decorator.andThen(d.decorator());
        }
        return decorator.apply(delegate);
    }

    /**
     * Returns a decorator with its order if the specified {@code annotation} is one of the user-defined
     * decorator annotation.
     */
    @Nullable
    private static DecoratorAndOrder userDefinedDecorator(Annotation annotation,
                                                          List<DependencyInjector> dependencyInjectors) {
        // User-defined decorator MUST be annotated with @DecoratorFactory annotation.
        final DecoratorFactory d = decoratorFactory(annotation);
        if (d == null) {
            return null;
        }

        // In case of user-defined decorator, we need to create a new decorator from its factory.
        @SuppressWarnings("unchecked")
        final DecoratorFactoryFunction<Annotation> factory = AnnotatedServiceFactory
                .getInstance(d, DecoratorFactoryFunction.class, dependencyInjectors);

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
        return new DecoratorAndOrder(annotation, factory.newDecorator(annotation), order);
    }

    @Nullable
    private static DecoratorFactory decoratorFactory(Annotation annotation) {
        return AnnotationUtil.findFirstDeclared(annotation.annotationType(), DecoratorFactory.class);
    }

    /**
     * Returns a new decorator which decorates an {@link HttpService} by the specified
     * {@link Decorator}.
     */
    private static Function<? super HttpService, ? extends HttpService> newDecorator(
            Decorator decorator, List<DependencyInjector> dependencyInjectors) {
        return service -> service.decorate(
                AnnotatedServiceFactory.getInstance(decorator, DecoratingHttpServiceFunction.class,
                                                    dependencyInjectors));
    }

    private DecoratorAnnotationUtil() {}

    /**
     * An internal class to hold a decorator with its order.
     */
    public static final class DecoratorAndOrder {
        // Keep the specified annotation for testing purpose.
        private final Annotation annotation;
        private final Function<? super HttpService, ? extends HttpService> decorator;
        private final int order;

        private DecoratorAndOrder(Annotation annotation,
                                  Function<? super HttpService, ? extends HttpService> decorator,
                                  int order) {
            this.annotation = annotation;
            this.decorator = decorator;
            this.order = order;
        }

        @VisibleForTesting
        public Annotation annotation() {
            return annotation;
        }

        public Function<? super HttpService, ? extends HttpService> decorator() {
            return decorator;
        }

        public int order() {
            return order;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("annotation", annotation())
                              .add("decorator", decorator())
                              .add("order", order())
                              .toString();
        }
    }
}
