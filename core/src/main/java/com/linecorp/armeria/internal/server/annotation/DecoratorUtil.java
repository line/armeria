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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.getConstructors;
import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withModifier;
import static org.reflections.ReflectionUtils.withName;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.AnnotationUtil.FindOption;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.Decorators;

/**
 * A utility class for {@link Decorator}.
 */
public final class DecoratorUtil {

    /**
     * An instance map for decorators.
     */
    private static final ClassValue<Object> instanceCache = new ClassValue<Object>() {
        @Override
        protected Object computeValue(Class<?> type) {
            try {
                return getInstance0(type);
            } catch (Exception e) {
                throw new IllegalStateException("A class must have an accessible default constructor: " +
                                                type.getName(), e);
            }
        }
    };

    /**
     * Returns the list of {@link Decorator} annotated methods.
     */
    public static List<Method> decoratorMethods(Class<?> clazz) {
        return getAllMethods(clazz, withModifier(Modifier.PUBLIC))
                .stream()
                // Lookup super classes just in case if the object is a proxy.
                .filter(m -> AnnotationUtil.getAnnotations(m, FindOption.LOOKUP_SUPER_CLASSES)
                                           .stream()
                                           .map(Annotation::annotationType)
                                           .anyMatch(a -> a == Decorator.class || a == Decorators.class))
                .collect(toImmutableList());
    }

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

    public static List<DecoratorAndOrder> collectDecorators(Class<?> clazz) {
        final List<DecoratorAndOrder> decorators = new ArrayList<>();

        // Class-level decorators are applied before method-level decorators.
        collectDecorators(decorators, AnnotationUtil.getAllAnnotations(clazz));

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
                list.add(new DecoratorAndOrder(d, newDecorator(d), d.order()));
                continue;
            }

            if (annotation instanceof Decorators) {
                final Decorator[] decorators = ((Decorators) annotation).value();
                for (final Decorator d : decorators) {
                    list.add(new DecoratorAndOrder(d, newDecorator(d), d.order()));
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
                assert method != null : "No 'value' method is found from " + annotation;
                final Annotation[] decorators = (Annotation[]) method.invoke(annotation);
                for (final Annotation decorator : decorators) {
                    udd = userDefinedDecorator(decorator);
                    if (udd == null) {
                        break;
                    }
                    list.add(udd);
                }
            } catch (Throwable ignore) {
                // The annotation may be a container of a decorator or may be not, so we just ignore
                // any exception from this clause.
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
     * Returns a cached instance of the specified {@link Class} which is specified in the given
     * {@link Annotation}.
     */
    static <T> T getInstance(Annotation annotation, Class<T> expectedType) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> clazz = (Class<? extends T>) invokeValueMethod(annotation);
            return expectedType.cast(instanceCache.get(clazz));
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                    "A class specified in @" + annotation.annotationType().getSimpleName() +
                    " annotation cannot be cast to " + expectedType, e);
        }
    }

    private static <T> T getInstance0(Class<? extends T> clazz) throws Exception {
        @SuppressWarnings("unchecked")
        final Constructor<? extends T> constructor =
                Iterables.getFirst(getConstructors(clazz, withParametersCount(0)), null);
        assert constructor != null : "No default constructor is found from " + clazz.getName();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * Returns a decorator with its order if the specified {@code annotation} is one of the user-defined
     * decorator annotation.
     */
    @Nullable
    private static DecoratorAndOrder userDefinedDecorator(Annotation annotation) {
        // User-defined decorator MUST be annotated with @DecoratorFactory annotation.
        final DecoratorFactory d = AnnotationUtil.findFirstDeclared(annotation.annotationType(),
                                                                    DecoratorFactory.class);
        if (d == null) {
            return null;
        }

        // In case of user-defined decorator, we need to create a new decorator from its factory.
        @SuppressWarnings("unchecked")
        final DecoratorFactoryFunction<Annotation> factory = getInstance(d, DecoratorFactoryFunction.class);

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

    /**
     * Returns a new decorator which decorates an {@link HttpService} by the specified
     * {@link Decorator}.
     */
    private static Function<? super HttpService, ? extends HttpService> newDecorator(Decorator decorator) {
        return service -> service.decorate(getInstance(decorator, DecoratingHttpServiceFunction.class));
    }

    /**
     * Returns an object which is returned by {@code value()} method of the specified annotation {@code a}.
     */
    private static Object invokeValueMethod(Annotation a) {
        try {
            final Method method = Iterables.getFirst(getMethods(a.annotationType(), withName("value")), null);
            assert method != null : "No 'value' method is found from " + a;
            return method.invoke(a);
        } catch (Exception e) {
            throw new IllegalStateException("An annotation @" + a.annotationType().getSimpleName() +
                                            " must have a 'value' method", e);
        }
    }

    private DecoratorUtil() {}

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
