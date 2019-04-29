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
import java.util.Arrays;
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
 * @param <B> the self type
 * @param <V> {@link ServerBuilder} or {@link VirtualHostBuilder} that you create this builder from
 *
 * @see RouteBuilder
 * @see VirtualHostRouteBuilder
 */
public abstract class AbstractRouteBuilder<B extends AbstractRouteBuilder<B, V>, V> {

    private static final EnumSet<HttpMethod> defaultMethods =
            EnumSet.of(OPTIONS, GET, HEAD, POST, PUT, PATCH, DELETE, TRACE, CONNECT);

    @Nullable
    private PathMapping defaultPathMapping;

    private Set<HttpMethod> methods = defaultMethods;

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

    @SuppressWarnings("unchecked")
    final B self() {
        return (B) this;
    }

    /**
     * Sets the path pattern that the {@linkplain #service(Service) service} is bound to. The {@link Service}
     * is found when the path of a request matches the path pattern and the {@link HttpMethod} of the request
     * is one of the {@link HttpMethod}s set by {@link #methods(HttpMethod...)}.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B path(String pathPattern) {
        defaultPathMapping = PathMapping.of(requireNonNull(pathPattern, "pathPattern"));
        return self();
    }

    /**
     * Sets the specified prefix which is a directory that the {@linkplain #service(Service) service}
     * is bound under. The {@link Service} is found when the path of a request is under the prefix and the
     * {@link HttpMethod} of the request is one of the {@link HttpMethod}s set by
     * {@link #methods(HttpMethod...)}.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B pathUnder(String prefix) {
        defaultPathMapping = PathMapping.ofPrefix(requireNonNull(prefix, "prefix"));
        return self();
    }

    /**
     * Sets the specified {@link PathMapping} that the {@linkplain #service(Service) service} is bound.
     * The {@link Service} is found when the path of a request matches the {@link PathMapping} and the
     * {@link HttpMethod} of the request is one of the {@link HttpMethod}s set by
     * {@link #methods(HttpMethod...)}.
     *
     * @throws IllegalArgumentException if the {@link PathMapping} has conditions beyond the path pattern,
     *                                  i.e. the {@link PathMapping} created by
     *                                  {@link PathMapping#withHttpHeaderInfo(Set, List, List)}
     */
    public B pathMapping(PathMapping pathMapping) {
        requireNonNull(pathMapping, "pathMapping");
        checkArgument(pathMapping.hasPathPatternOnly(),
                      "pathMapping: %s " +
                      "(expected: the path mapping which has only the path patterns as its condition)",
                      pathMapping.getClass().getSimpleName());
        defaultPathMapping = pathMapping;
        return self();
    }

    /**
     * Sets the {@link HttpMethod}s that the {@linkplain #service(Service) service} supports.
     * If not set, all {@link HttpMethod}s except {@link HttpMethod#UNKNOWN} are set.
     *
     * @see #path(String)
     * @see #pathUnder(String)
     * @see #pathMapping(PathMapping)
     */
    public B methods(HttpMethod... methods) {
        methods(EnumSet.copyOf(Arrays.asList(methods)));
        return self();
    }

    /**
     * Sets the {@link HttpMethod}s that the {@linkplain #service(Service) service} supports.
     * If not set, all {@link HttpMethod}s are set.
     *
     * @see #path(String)
     * @see #pathUnder(String)
     * @see #pathMapping(PathMapping)
     */
    public B methods(Set<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!methods.isEmpty(), "methods can't be empty");
        this.methods = EnumSet.copyOf(methods);
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#GET} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B get(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(GET));
        return self();
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
     * Sets the path pattern and {@link HttpMethod#POST} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B post(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(POST));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#PUT} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B put(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(PUT));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#PATCH} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B patch(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(PATCH));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#DELETE} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B delete(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(DELETE));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#OPTIONS} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B options(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(OPTIONS));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#HEAD} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B head(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(HEAD));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#TRACE} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B trace(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(TRACE));
        return self();
    }

    /**
     * Sets the path pattern and {@link HttpMethod#CONNECT} that the {@linkplain #service(Service) service} is
     * bound to and supports.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public B connect(String pathPattern) {
        addPathMapping(pathPattern, EnumSet.of(CONNECT));
        return self();
    }

    /**
     * Sets {@link MediaType}s that the {@link Service} consumes. If not set,
     * the {@linkplain #service(Service) service} accepts all media types.
     */
    public B consumes(MediaType... consumeTypes) {
        consumes(ImmutableList.copyOf(requireNonNull(consumeTypes, "consumeTypes")));
        return self();
    }

    /**
     * Sets {@link MediaType}s that the {@link Service} consumes. If not set,
     * the {@linkplain #service(Service) service} accepts all media types.
     */
    public B consumes(List<MediaType> consumeTypes) {
        ensureUniqueTypes(consumeTypes, "consumeTypes");
        this.consumeTypes = ImmutableList.copyOf(consumeTypes);
        return self();
    }

    private static List<MediaType> ensureUniqueTypes(List<MediaType> types, String typeName) {
        requireNonNull(types, typeName);
        final Set<MediaType> set = new HashSet<>();
        for (final MediaType type : types) {
            if (!set.add(type)) {
                throw new IllegalArgumentException(
                        "duplicated media type in " + typeName + ": " + type);
            }
        }
        return types;
    }

    /**
     * Sets {@link MediaType}s that the {@linkplain #service(Service) service} produces to be used in
     * content negotiation. See <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">Accept header</a>
     * for more information.
     */
    public B produces(MediaType... produceTypes) {
        this.produceTypes = ImmutableList.copyOf(requireNonNull(produceTypes, "produceTypes"));
        return self();
    }

    /**
     * Sets {@link MediaType}s that the {@linkplain #service(Service) service} produces to be used in
     * content negotiation. See <a href="https://tools.ietf.org/html/rfc7231#section-5.3.2">Accept header</a>
     * for more information.
     */
    public B produces(List<MediaType> produceTypes) {
        ensureUniqueTypes(produceTypes, "produceTypes");
        this.produceTypes = ImmutableList.copyOf(produceTypes);
        return self();
    }

    /**
     * Sets the timeout of an HTTP request. If not set, {@link VirtualHost#requestTimeoutMillis()}
     * is used.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    public B requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout of an HTTP request in milliseconds. If not set,
     * {@link VirtualHost#requestTimeoutMillis()} is used.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public B requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return self();
    }

    /**
     * Sets the maximum allowed length of an HTTP request. If not set,
     * {@link VirtualHost#maxRequestLength()} is used.
     *
     * @param maxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    public B maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        return self();
    }

    /**
     * Sets whether the verbose response mode is enabled. When enabled, the service response will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service response will not expose such server-side details to the client.
     * If not set, {@link VirtualHost#verboseResponses()} is used.
     */
    public B verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return self();
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request of the
     * {@linkplain #service(Service) service}. If not set, {@link VirtualHost#requestContentPreviewerFactory()}
     * is used.
     */
    public B requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return self();
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP response of the
     * {@linkplain #service(Service) service}. If not set, {@link VirtualHost#responseContentPreviewerFactory()}
     * is used.
     */
    public B responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        responseContentPreviewerFactory = requireNonNull(factory, "factory");
        return self();
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
    public B contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of the
     * {@linkplain #service(Service) service}. The previewer is enabled only if the content type of an HTTP
     * request/response meets any of the following conditions:
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
    public B contentPreview(int length, Charset defaultCharset) {
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request/response of the
     * {@linkplain #service(Service) service}.
     */
    public B contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return self();
    }

    /**
     * Decorates the {@linkplain #service(Service) service} with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates the {@linkplain #service(Service) service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    B decorator(Function<T, R> decorator) {
        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return self();
    }

    /**
     * Sets the {@link Service} and returns the {@link V} that you create this
     * {@link B} from.
     *
     * @throws IllegalStateException if the path that the {@link Service} will be bound to is not specified
     *
     * @see #path(String)
     * @see #pathUnder(String)
     * @see #pathMapping(PathMapping)
     */
    public abstract V service(Service<HttpRequest, HttpResponse> service);

    abstract void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder);

    void build(Service<HttpRequest, HttpResponse> service) {
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
