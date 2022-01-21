/*
 * Copyright 2022 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.internal.server.annotation.DecoratorUtil.DecoratorAndOrder;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecoratorFactoryFunction;
import com.linecorp.armeria.server.annotation.decorator.RateLimitingDecorator;
import com.linecorp.armeria.server.annotation.decorator.RateLimitingDecoratorFactoryFunction;

class DecoratorUtilTest {

    @Test
    void ofNoOrdering() throws NoSuchMethodException {
        final List<DecoratorAndOrder> list =
                DecoratorUtil.collectDecorators(TestClass.class,
                                                TestClass.class.getMethod("noOrdering"));
        assertThat(values(list)).containsExactly(Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 Decorator2.class);
        assertThat(orders(list)).containsExactly(0, 0, 0, 0);

        final LoggingDecorator info = (LoggingDecorator) list.get(1).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
        final LoggingDecorator trace = (LoggingDecorator) list.get(2).annotation();
        assertThat(trace.requestLogLevel()).isEqualTo(LogLevel.TRACE);
    }

    @Test
    void ofMethodScopeOrdering() throws NoSuchMethodException {
        final List<DecoratorAndOrder> list =
                DecoratorUtil.collectDecorators(TestClass.class,
                                                TestClass.class.getMethod("methodScopeOrdering"));
        assertThat(values(list)).containsExactly(Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 RateLimitingDecoratorFactoryFunction.class,
                                                 Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 Decorator2.class);
        assertThat(orders(list)).containsExactly(0, 0, 0, 1, 2, 3);

        final LoggingDecorator info = (LoggingDecorator) list.get(1).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
        final RateLimitingDecorator limit = (RateLimitingDecorator) list.get(2).annotation();
        assertThat(limit.value()).isEqualTo(1);
        final LoggingDecorator trace = (LoggingDecorator) list.get(4).annotation();
        assertThat(trace.requestLogLevel()).isEqualTo(LogLevel.TRACE);
    }

    @Test
    void ofGlobalScopeOrdering() throws NoSuchMethodException {
        final List<DecoratorAndOrder> list =
                DecoratorUtil.collectDecorators(TestClass.class,
                                                TestClass.class.getMethod("globalScopeOrdering"));
        assertThat(values(list)).containsExactly(LoggingDecoratorFactoryFunction.class,
                                                 Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 Decorator2.class);
        assertThat(orders(list)).containsExactly(-1, 0, 0, 1);

        final LoggingDecorator trace = (LoggingDecorator) list.get(0).annotation();
        assertThat(trace.requestLogLevel()).isEqualTo(LogLevel.TRACE);
        final LoggingDecorator info = (LoggingDecorator) list.get(2).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
    }

    @Test
    void ofUserDefinedRepeatableDecorator() throws NoSuchMethodException {
        final List<DecoratorAndOrder> list =
                DecoratorUtil.collectDecorators(TestClass.class,
                                                TestClass.class.getMethod("userDefinedRepeatableDecorator"));
        assertThat(values(list)).containsExactly(Decorator1.class,
                                                 LoggingDecoratorFactoryFunction.class,
                                                 UserDefinedRepeatableDecoratorFactory.class,
                                                 Decorator2.class,
                                                 UserDefinedRepeatableDecoratorFactory.class);
        assertThat(orders(list)).containsExactly(0, 0, 1, 2, 3);

        final LoggingDecorator info = (LoggingDecorator) list.get(1).annotation();
        assertThat(info.requestLogLevel()).isEqualTo(LogLevel.INFO);
        final UserDefinedRepeatableDecorator udd1 = (UserDefinedRepeatableDecorator) list.get(2).annotation();
        assertThat(udd1.value()).isEqualTo(1);
        final UserDefinedRepeatableDecorator udd2 = (UserDefinedRepeatableDecorator) list.get(4).annotation();
        assertThat(udd2.value()).isEqualTo(2);
    }

    private static List<Class<?>> values(List<DecoratorAndOrder> list) {
        return list.stream()
                   .map(DecoratorAndOrder::annotation)
                   .map(annotation -> {
                       if (annotation instanceof Decorator) {
                           return ((Decorator) annotation).value();
                       }
                       final DecoratorFactory factory =
                               annotation.annotationType().getAnnotation(DecoratorFactory.class);
                       if (factory != null) {
                           return factory.value();
                       }
                       throw new Error("Should not reach here.");
                   })
                   .collect(Collectors.toList());
    }

    private static List<Integer> orders(List<DecoratorAndOrder> list) {
        return list.stream().map(DecoratorAndOrder::order).collect(Collectors.toList());
    }

    static class Decorator1 implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    static class Decorator2 implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                                  HttpRequest req) throws Exception {
            return delegate.serve(ctx, req);
        }
    }

    @Decorator(Decorator1.class)
    @LoggingDecorator(requestLogLevel = LogLevel.INFO)
    static class TestClass {

        @LoggingDecorator
        @Decorator(Decorator2.class)
        public String noOrdering() {
            return "";
        }

        @RateLimitingDecorator(1)
        @Decorator(value = Decorator1.class, order = 1)
        @LoggingDecorator(order = 2)
        @Decorator(value = Decorator2.class, order = 3)
        public String methodScopeOrdering() {
            return "";
        }

        @Decorator(value = Decorator2.class, order = 1)
        @LoggingDecorator(order = -1)
        public String globalScopeOrdering() {
            return "";
        }

        @UserDefinedRepeatableDecorator(value = 1, order = 1)
        @Decorator(value = Decorator2.class, order = 2)
        @UserDefinedRepeatableDecorator(value = 2, order = 3)
        public String userDefinedRepeatableDecorator() {
            return "";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface UserDefinedRepeatableDecorators {
        UserDefinedRepeatableDecorator[] value();
    }

    @DecoratorFactory(UserDefinedRepeatableDecoratorFactory.class)
    @Repeatable(UserDefinedRepeatableDecorators.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @interface UserDefinedRepeatableDecorator {

        // To identify the decorator instance.
        int value();

        // For ordering.
        int order() default 0;
    }

    private static class UserDefinedRepeatableDecoratorFactory
            implements DecoratorFactoryFunction<UserDefinedRepeatableDecorator> {
        @Override
        public Function<? super HttpService, ? extends HttpService> newDecorator(
                UserDefinedRepeatableDecorator parameter) {
            return service -> new SimpleDecoratingHttpService(service) {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return service.serve(ctx, req);
                }
            };
        }
    }
}
