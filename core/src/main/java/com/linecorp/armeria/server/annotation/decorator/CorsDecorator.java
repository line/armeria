/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.cors.CorsDecoratorFactoryFunction;
import com.linecorp.armeria.server.cors.CorsPolicyBuilder;
import com.linecorp.armeria.server.cors.CorsService;

/**
 * A {@link CorsService} decorator for annotated HTTP services.
 */
@Repeatable(CorsDecorators.class)
@DecoratorFactory(CorsDecoratorFactoryFunction.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface CorsDecorator {
    /**
     * Allowed origins.
     * Sets this property to be {@code "*"} to allow any origin.
     */
    String[] origins();

    /**
     * The path patterns that this policy is supposed to be applied to. If unspecified, all paths would be
     * accepted.
     */
    String[] pathPatterns() default {};

    /**
     * The allowed HTTP request methods that should be returned in the
     * CORS {@code "Access-Control-Allow-Methods"} response header.
     *
     * @see CorsPolicyBuilder#allowRequestMethods(HttpMethod...)
     */
    HttpMethod[] allowedRequestMethods() default {};

    /**
     * The value of the CORS {@code "Access-Control-Max-Age"} response header which enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     *
     * @see CorsPolicyBuilder#maxAge(long)
     */
    long maxAge() default 0;

    /**
     * The headers to be exposed to calling clients.
     *
     * @see CorsPolicyBuilder#exposeHeaders(CharSequence...)
     */
    String[] exposedHeaders() default {};

    /**
     * Enables to allow all HTTP headers.
     *
     * @see CorsPolicyBuilder#allowAllRequestHeaders()
     */
    boolean allowAllRequestHeaders() default false;

    /**
     * The headers that should be returned in the CORS {@code "Access-Control-Allow-Headers"}
     * response header.
     *
     * @see CorsPolicyBuilder#allowRequestHeaders(CharSequence...)
     */
    String[] allowedRequestHeaders() default {};

    /**
     * Determines if cookies are allowed to be added to CORS requests.
     * Settings this to be {@code true} will set the CORS {@code "Access-Control-Allow-Credentials"}
     * response header to {@code "true"}.
     *
     * <p>If unset, will be {@code false}.
     *
     * @see CorsPolicyBuilder#allowCredentials()
     */
    boolean credentialsAllowed() default false;

    /**
     * Determines if a {@code "null"} origin is allowed.
     *
     * <p>If unset, will be {@code false}.
     *
     * @see CorsPolicyBuilder#allowNullOrigin()
     */
    boolean nullOriginAllowed() default false;

    /**
     * The HTTP response headers that should be added to a CORS preflight response.
     *
     * @see CorsPolicyBuilder#preflightResponseHeader(CharSequence, Object...)
     */
    AdditionalHeader[] preflightRequestHeaders() default {};

    /**
     * Determines if no preflight response headers should be added to a CORS preflight response.
     *
     * <p>If unset, will be {@code false}.
     *
     * @see CorsPolicyBuilder#disablePreflightResponseHeaders()
     */
    boolean preflightRequestDisabled() default false;
}
