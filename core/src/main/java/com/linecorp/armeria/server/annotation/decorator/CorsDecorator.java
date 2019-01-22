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
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.KeyValue;
import com.linecorp.armeria.server.cors.CorsDecoratorFactoryFunction;

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
     * Specifies the allowed set of HTTP request methods that should be returned in the
     * CORS {@code "Access-Control-Request-Method"} response header.
     */
    HttpMethod[] allowedRequestMethods() default {};

    /**
     * Sets the CORS {@code "Access-Control-Max-Age"} response header and enables the
     * caching of the preflight response for the specified time. During this time no preflight
     * request will be made.
     */
    long maxAge() default 0;

    /**
     * Specifies the headers to be exposed to calling clients.
     *
     * <p>During a simple CORS request, only certain response headers are made available by the
     * browser, for example using:
     * <pre>{@code
     * xhr.getResponseHeader("Content-Type");
     * }</pre>
     *
     * <p>The headers that are available by default are:
     * <ul>
     *   <li>{@code Cahce-Control}</li>
     *   <li>{@code Content-Language}</li>
     *   <li>{@code Content-Type}</li>
     *   <li>{@code Expires}</li>
     *   <li>{@code Last-Modified}</li>
     *   <li>{@code Pragma}</li>
     * </ul>
     *
     * <p>To expose other headers they need to be specified which is what this method enables by
     * adding the headers to the CORS {@code "Access-Control-Expose-Headers"} response header.
     */
    String[] exposedHeaders() default {};

    /**
     * Specifies the headers that should be returned in the CORS {@code "Access-Control-Allow-Headers"}
     * response header.
     *
     * <p>If a client specifies headers on the request, for example by calling:
     * <pre>{@code
     * xhr.setRequestHeader('My-Custom-Header', 'SomeValue');
     * }</pre>
     * The server will receive the above header name in the {@code "Access-Control-Request-Headers"} of the
     * preflight request. The server will then decide if it allows this header to be sent for the
     * real request (remember that a preflight is not the real request but a request asking the server
     * if it allows a request).
     */
    String[] allowedRequestHeaders() default {};

    /**
     * Enables cookies to be added to CORS requests.
     * Calling this method will set the CORS {@code "Access-Control-Allow-Credentials"} response header
     * to {@code true}. By default, cookies are not included in CORS requests.
     * If unset, will be {@code false}
     */
    boolean credentialAllowed() default false;

    /**
     * Enables a successful CORS response with a {@code "null"} value for the CORS response header
     * {@code "Access-Control-Allow-Origin"}. Web browsers may set the {@code "Origin"} request header to
     * {@code "null"} if a resource is loaded from the local file system.
     *
     * <p>If unset, will be {@code false}
     */
    boolean nullOriginAllowed() default false;

    /**
     * Specifies HTTP response headers that should be added to a CORS preflight response.
     *
     * <p>An intermediary like a load balancer might require that a CORS preflight request
     * have certain headers set. This enables such headers to be added.
     */
    KeyValue[] preflightRequestHeaders() default {};

    /**
     * Specifies that no preflight response headers should be added to a preflight response.
     * If unset, will be {@code false}
     */
    boolean preflightRequestDisabled() default false;

    /**
     * Specifies that a CORS request should be rejected if it's invalid before being
     * further processing.
     *
     * <p>CORS headers are set after a request is processed. This may not always be desired
     * and this setting will check that the Origin is valid and if it is not valid no
     * further processing will take place, and a error will be returned to the calling client.
     *
     * <p>This field will be ignored if {@link CorsDecorators} is used.
     * Sets {@link CorsDecorators#shortCircuit()} to be true instead.
     */
    boolean shortCircuit() default false;
}
