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
package com.linecorp.armeria.server.annotation.decorator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.logging.LoggingService;

/**
 * A {@link LoggingService} decorator for annotated HTTP services.
 */
@DecoratorFactory(LoggingDecoratorFactoryFunction.class)
@Repeatable(LoggingDecorators.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface LoggingDecorator {

    /**
     * The {@link LogLevel} to use when logging requests. If unset, will use {@link LogLevel#TRACE}.
     */
    LogLevel requestLogLevel() default LogLevel.TRACE;

    /**
     * The {@link LogLevel} to use when logging successful responses (e.g., no unhandled exception).
     * If unset, will use {@link LogLevel#TRACE}.
     */
    LogLevel successfulResponseLogLevel() default LogLevel.TRACE;

    /**
     * The {@link LogLevel} to use when logging failure responses (e.g., failed with an exception).
     * If unset, will use {@link LogLevel#WARN}.
     */
    LogLevel failureResponseLogLevel() default LogLevel.WARN;

    /**
     * The rate at which to sample requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged. The random sampling is appropriate for low-traffic
     * (ex servers that each receive &lt;100K requests). If unset, all requests will be logged.
     */
    float samplingRate() default 1.0f;

    /**
     * The rate at which to sample failed requests to log. Any number between {@code 0.0} and {@code 1.0} will
     * cause a random sample of the requests to be logged. The random sampling is appropriate for low-traffic
     * (ex servers that each receive &lt;100K requests). If unset, all requests will be logged.
     */
    float failedSamplingRate() default 1.0f;

    /**
     * The order of decoration, where a {@link Decorator} of lower value will be applied first.
     */
    int order() default 0;
}
