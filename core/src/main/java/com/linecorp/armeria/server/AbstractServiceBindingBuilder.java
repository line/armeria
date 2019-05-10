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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.HttpMethod.CONNECT;
import static com.linecorp.armeria.common.HttpMethod.DELETE;
import static com.linecorp.armeria.common.HttpMethod.GET;
import static com.linecorp.armeria.common.HttpMethod.HEAD;
import static com.linecorp.armeria.common.HttpMethod.OPTIONS;
import static com.linecorp.armeria.common.HttpMethod.PATCH;
import static com.linecorp.armeria.common.HttpMethod.POST;
import static com.linecorp.armeria.common.HttpMethod.PUT;
import static com.linecorp.armeria.common.HttpMethod.TRACE;
import static com.linecorp.armeria.server.ServerConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateRequestTimeoutMillis;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

/**
 * A builder class for binding a {@link Service} fluently.
 *
 * @see ServiceBindingBuilder
 * @see VirtualHostServiceBindingBuilder
 */
abstract class AbstractServiceBindingBuilder {

    @Nullable
    private PathMapping defaultPathMapping;

    private Set<HttpMethod> methods = HttpMethod.knownMethods();

    private final Map<PathMapping, Set<HttpMethod>> pathMappings = new HashMap<>();

    private List<MediaType> consumeTypes = ImmutableList.of();
    private List<MediaType> produceTypes = ImmutableList.of();

    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private ContentPreviewerFactory requestContentPreviewerFactory;
    @Nullable
    private ContentPreviewerFactory responseContentPreviewerFactory;
    @Nullable
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;

    /**
     * Sets the path pattern that a {@link Service} will be bound to.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder path(String pathPattern) {
        defaultPathMapping = PathMapping.of(requireNonNull(pathPattern, "pathPattern"));
        return this;
    }

    /**
     * Sets the specified prefix which is a directory that a {@link Service} will be bound under.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder pathUnder(String prefix) {
        defaultPathMapping = PathMapping.ofPrefix(requireNonNull(prefix, "prefix"));
        return this;
    }

    /**
     * Sets the specified {@link PathMapping} that a {@link Service} will be bound to.
     *
     * @throws IllegalArgumentException if the {@link PathMapping} has conditions beyond the path pattern,
     *                                  i.e. the {@link PathMapping} created by
     *                                  {@link PathMapping#withHttpHeaderInfo(Set, List, List)}
     */
    public AbstractServiceBindingBuilder pathMapping(PathMapping pathMapping) {
        requireNonNull(pathMapping, "pathMapping");
        if (!pathMapping.hasPathPatternOnly()) {
            throw new IllegalArgumentException(
                    "pathMapping: " + pathMapping.getClass().getSimpleName() +
                    " (expected: the path mapping which has only the path patterns as its condition)");
        }
        defaultPathMapping = pathMapping;
        return this;
    }

    /**
     * Sets the {@link HttpMethod}s that a {@link Service} will support. If not set,
     * {@link HttpMethod#knownMethods()}s are set.
     *
     * @see #path(String)
     * @see #pathUnder(String)
     * @see #pathMapping(PathMapping)
     */
    public AbstractServiceBindingBuilder methods(HttpMethod... methods) {
        return methods(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    /**
     * Sets the {@link HttpMethod}s that a {@link Service} will support. If not set,
     * {@link HttpMethod#knownMethods()}s are set.
     *
     * @see #path(String)
     * @see #pathUnder(String)
     * @see #pathMapping(PathMapping)
     */
    public AbstractServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "methods can't be empty");
        this.methods = ImmutableSet.copyOf(methods);
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#GET} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder get(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(GET));
        return this;
    }

    private void addPathMapping(String pathPattern, Set<HttpMethod> methods) {
        final PathMapping pathMapping = PathMapping.of(requireNonNull(pathPattern, "pathPattern"));
        addPathMapping(pathMapping, methods);
    }

    private void addPathMapping(PathMapping pathMapping, Set<HttpMethod> methods) {
        final Set<HttpMethod> methodSet = pathMappings.computeIfAbsent(
                pathMapping, key -> EnumSet.noneOf(HttpMethod.class));

        for (HttpMethod method : methods) {
            if (!methodSet.add(method)) {
                throw new IllegalArgumentException("duplicate HTTP method: " + method +
                                                   ", for: " + pathMapping);
            }
        }
    }

    /**
     * Sets the path pattern and {@link HttpMethod#POST} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder post(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(POST));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#PUT} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder put(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(PUT));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#PATCH} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder patch(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(PATCH));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#DELETE} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder delete(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(DELETE));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#OPTIONS} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder options(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(OPTIONS));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#HEAD} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder head(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(HEAD));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#TRACE} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder trace(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(TRACE));
        return this;
    }

    /**
     * Sets the path pattern and {@link HttpMethod#CONNECT} that a {@link Service} will be bound to and support.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public AbstractServiceBindingBuilder connect(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(CONNECT));
        return this;
    }

    /**
     * Sets {@link MediaType}s that a {@link Service} will consume. If not set, the {@link Service}
     * will accept all media types.
     */
    public AbstractServiceBindingBuilder consumes(MediaType... consumeTypes) {
        consumes(ImmutableList.copyOf(requireNonNull(consumeTypes, "consumeTypes")));
        return this;
    }

    /**
     * Sets {@link MediaType}s that a {@link Service} will consume. If not set, the {@link Service}
     * will accept all media types.
     */
    public AbstractServiceBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        ensureUniqueTypes(consumeTypes, "consumeTypes");
        this.consumeTypes = ImmutableList.copyOf(consumeTypes);
        return this;
    }

    private static void ensureUniqueTypes(Iterable<MediaType> types, String typeName) {
        requireNonNull(types, typeName);
        final Set<MediaType> set = new HashSet<>();
        for (final MediaType type : types) {
            if (!set.add(type)) {
                throw new IllegalArgumentException(
                        "duplicated media type in " + typeName + ": " + type);
            }
        }
    }

    /**
     * Sets {@link MediaType}s that a {@link Service} will produce to be used in
     * content negotiation. See <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">Accept header</a>
     * for more information.
     */
    public AbstractServiceBindingBuilder produces(MediaType... produceTypes) {
        this.produceTypes = ImmutableList.copyOf(requireNonNull(produceTypes, "produceTypes"));
        return this;
    }

    /**
     * Sets {@link MediaType}s that a {@link Service} will produce to be used in
     * content negotiation. See <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">Accept header</a>
     * for more information.
     */
    public AbstractServiceBindingBuilder produces(Iterable<MediaType> produceTypes) {
        ensureUniqueTypes(produceTypes, "produceTypes");
        this.produceTypes = ImmutableList.copyOf(produceTypes);
        return this;
    }

    /**
     * Sets the timeout of an HTTP request. If not set, {@link VirtualHost#requestTimeoutMillis()}
     * is used.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    public AbstractServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout of an HTTP request in milliseconds. If not set,
     * {@link VirtualHost#requestTimeoutMillis()} is used.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public AbstractServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    /**
     * Sets the maximum allowed length of an HTTP request. If not set,
     * {@link VirtualHost#maxRequestLength()} is used.
     *
     * @param maxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    public AbstractServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        return this;
    }

    /**
     * Sets whether the verbose response mode is enabled. When enabled, the service response will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service response will not expose such server-side details to the client.
     * If not set, {@link VirtualHost#verboseResponses()} is used.
     */
    public AbstractServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request of a {@link Service}.
     * If not set, {@link VirtualHost#requestContentPreviewerFactory()}
     * is used.
     */
    public AbstractServiceBindingBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP response of a {@link Service}.
     * If not set, {@link VirtualHost#responseContentPreviewerFactory()}
     * is used.
     */
    public AbstractServiceBindingBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        responseContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of the {@link Service}.
     * The previewer is enabled only if the content type of an HTTP request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     */
    public AbstractServiceBindingBuilder contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of a {@link Service}.
     * The previewer is enabled only if the content type of an HTTP request/response meets any of
     * the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public AbstractServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request/response of a {@link Service}.
     */
    public AbstractServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return this;
    }

    /**
     * Decorates a {@link Service} with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates the {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    AbstractServiceBindingBuilder decorator(Function<T, R> decorator) {
        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return this;
    }

    abstract void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder);

    final void build0(Service<HttpRequest, HttpResponse> service) {
        if (defaultPathMapping != null) {
            addPathMapping(defaultPathMapping, methods);
        }
        checkState(!pathMappings.isEmpty(),
                   "Should set a path that the service is bound to before calling this.");

        for (Entry<PathMapping, Set<HttpMethod>> entry : pathMappings.entrySet()) {
            final PathMapping pathMapping =
                    entry.getKey().withHttpHeaderInfo(entry.getValue(), consumeTypes, produceTypes);
            final ServiceConfigBuilder serviceConfigBuilder =
                    new ServiceConfigBuilder(pathMapping, decorate(service));
            if (requestTimeoutMillis != null) {
                serviceConfigBuilder.requestTimeoutMillis(requestTimeoutMillis);
            }
            if (maxRequestLength != null) {
                serviceConfigBuilder.maxRequestLength(maxRequestLength);
            }
            if (verboseResponses != null) {
                serviceConfigBuilder.verboseResponses(verboseResponses);
            }
            if (requestContentPreviewerFactory != null) {
                serviceConfigBuilder.requestContentPreviewerFactory(requestContentPreviewerFactory);
            }
            if (responseContentPreviewerFactory != null) {
                serviceConfigBuilder.responseContentPreviewerFactory(responseContentPreviewerFactory);
            }
            serviceConfigBuilder(serviceConfigBuilder);
        }
    }

    private Service<HttpRequest, HttpResponse> decorate(Service<HttpRequest, HttpResponse> service) {
        if (decorator == null) {
            return service;
        }

        return service.decorate(decorator);
    }
}
