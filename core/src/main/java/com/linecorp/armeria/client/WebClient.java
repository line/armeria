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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.nio.charset.Charset;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * An asynchronous web client.
 */
public interface WebClient extends ClientBuilderParams, Unwrappable {

    /**
     * Returns a {@link WebClient} without a base URI using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     */
    static WebClient of() {
        return DefaultWebClient.DEFAULT;
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@code uri} using the default options.
     *
     * @param uri the URI of the server endpoint
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     */
    static WebClient of(String uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link URI} using the default options.
     *
     * @param uri the {@link URI} of the server endpoint
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link URI} is not an HTTP scheme
     */
    static WebClient of(URI uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code protocol} using the default {@link ClientFactory} and the default
     * {@link ClientOptions}.
     *
     * @param protocol the session protocol of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     */
    static WebClient of(String protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} using the default {@link ClientFactory} and the default
     * {@link ClientOptions}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     */
    static WebClient of(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code protocol} and {@code path} using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     *
     * @param protocol the session protocol of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     */
    static WebClient of(String protocol, EndpointGroup endpointGroup, @Nullable String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} and {@code path} using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     */
    static WebClient of(SessionProtocol protocol, EndpointGroup endpointGroup, @Nullable String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(String)} and {@link WebClientBuilder#option(ClientOptionValue)}.
     */
    @Deprecated
    static WebClient of(String uri, ClientOptionValue<?>... options) {
        return builder(uri).options(options).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(String)} and {@link WebClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    static WebClient of(String uri, ClientOptions options) {
        return builder(uri).options(options).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@code uri} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(String)}, {@link WebClientBuilder#factory(ClientFactory)}
     *             and {@link WebClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    static WebClient of(ClientFactory factory, String uri, ClientOptionValue<?>... options) {
        return builder(uri).options(options).factory(factory).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@code uri} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(String)}, {@link WebClientBuilder#factory(ClientFactory)}
     *             and {@link WebClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    static WebClient of(ClientFactory factory, String uri, ClientOptions options) {
        return builder(uri).options(options).factory(factory).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(URI)} and {@link WebClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    static WebClient of(URI uri, ClientOptionValue<?>... options) {
        return builder(uri).options(options).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(URI)} and {@link WebClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    static WebClient of(URI uri, ClientOptions options) {
        return builder(uri).options(options).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link URI} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptionValue}s
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(URI)}, {@link WebClientBuilder#factory(ClientFactory)}
     *             and {@link WebClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    static WebClient of(ClientFactory factory, URI uri, ClientOptionValue<?>... options) {
        return builder(uri).options(options).factory(factory).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link URI} using an alternative
     * {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param uri the URI of the server endpoint
     * @param options the {@link ClientOptions}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@code uri} is not an HTTP scheme
     *
     * @deprecated Use {@link #builder(URI)}, {@link WebClientBuilder#factory(ClientFactory)}
     *             and {@link WebClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    static WebClient of(ClientFactory factory, URI uri, ClientOptions options) {
        return builder(uri).options(options).factory(factory).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the {@link SessionProtocol} using the default {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param options the {@link ClientOptionValue}s
     *
     * @deprecated Use {@link #builder(SessionProtocol, EndpointGroup)}
     *             and {@link WebClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    static WebClient of(SessionProtocol protocol, EndpointGroup endpointGroup,
                        ClientOptionValue<?>... options) {
        return builder(protocol, endpointGroup).options(options).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the {@link SessionProtocol} using the default {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param options the {@link ClientOptions}
     *
     * @deprecated Use {@link #builder(SessionProtocol, EndpointGroup)}
     *             and {@link WebClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    static WebClient of(SessionProtocol protocol, EndpointGroup endpointGroup, ClientOptions options) {
        return builder(protocol, endpointGroup).options(options).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the {@link SessionProtocol} using an alternative {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param options the {@link ClientOptionValue}s
     *
     * @deprecated Use {@link #builder(SessionProtocol, EndpointGroup)},
     *             {@link WebClientBuilder#factory(ClientFactory)}
     *             and {@link WebClientBuilder#options(ClientOptionValue[])}.
     */
    @Deprecated
    static WebClient of(ClientFactory factory, SessionProtocol protocol, EndpointGroup endpointGroup,
                        ClientOptionValue<?>... options) {
        return builder(protocol, endpointGroup).options(options).factory(factory).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the {@link SessionProtocol} using an alternative {@link ClientFactory}.
     *
     * @param factory an alternative {@link ClientFactory}
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param options the {@link ClientOptions}
     *
     * @deprecated Use {@link #builder(SessionProtocol, EndpointGroup)},
     *             {@link WebClientBuilder#factory(ClientFactory)}
     *             and {@link WebClientBuilder#options(ClientOptions)}.
     */
    @Deprecated
    static WebClient of(ClientFactory factory, SessionProtocol protocol, EndpointGroup endpointGroup,
                        ClientOptions options) {
        return builder(protocol, endpointGroup).options(options).factory(factory).build();
    }

    /**
     * Returns a new {@link WebClientBuilder} created without a base {@link URI}.
     */
    static WebClientBuilder builder() {
        return new WebClientBuilder();
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified base {@code uri}.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol} or the uri violates RFC 2396
     */
    static WebClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified base {@link URI}.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    static WebClientBuilder builder(URI uri) {
        return new WebClientBuilder(uri);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@code protocol}
     * and base {@link EndpointGroup}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    static WebClientBuilder builder(String protocol, EndpointGroup endpointGroup) {
        return builder(SessionProtocol.of(requireNonNull(protocol, "protocol")), endpointGroup);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@link SessionProtocol}
     * and base {@link EndpointGroup}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the fields
     *                                  in {@link SessionProtocol#httpValues()} or {@link SessionProtocol#httpsValues()}

     */
    static WebClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup, null);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@code protocol}.
     * base {@link EndpointGroup} and path.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    static WebClientBuilder builder(String protocol, EndpointGroup endpointGroup, @Nullable String path) {
        return builder(SessionProtocol.of(requireNonNull(protocol, "protocol")),
                       endpointGroup, path);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@link SessionProtocol},
     * base {@link EndpointGroup} and path.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    static WebClientBuilder builder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                                    @Nullable String path) {
        return new WebClientBuilder(sessionProtocol, endpointGroup, path);
    }

    /**
     * Sends the specified HTTP request.
     */
    HttpResponse execute(HttpRequest req);

    /**
     * Sends the specified HTTP request.
     */
    HttpResponse execute(AggregatedHttpRequest aggregatedReq);

    /**
     * Sends an empty HTTP request with the specified headers.
     */
    default HttpResponse execute(RequestHeaders headers) {
        return execute(HttpRequest.of(headers));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default HttpResponse execute(RequestHeaders headers, HttpData content) {
        return execute(HttpRequest.of(headers, content));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default HttpResponse execute(RequestHeaders headers, byte[] content) {
        return execute(HttpRequest.of(headers, HttpData.wrap(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default HttpResponse execute(RequestHeaders headers, String content) {
        return execute(HttpRequest.of(headers, HttpData.ofUtf8(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default HttpResponse execute(RequestHeaders headers, String content, Charset charset) {
        return execute(HttpRequest.of(headers, HttpData.of(charset, content)));
    }

    /**
     * Sends an HTTP OPTIONS request.
     */
    default HttpResponse options(String path) {
        return execute(RequestHeaders.of(HttpMethod.OPTIONS, path));
    }

    /**
     * Sends an HTTP GET request.
     */
    default HttpResponse get(String path) {
        return execute(RequestHeaders.of(HttpMethod.GET, path));
    }

    /**
     * Sends an HTTP HEAD request.
     */
    default HttpResponse head(String path) {
        return execute(RequestHeaders.of(HttpMethod.HEAD, path));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default HttpResponse post(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default HttpResponse post(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default HttpResponse post(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default HttpResponse post(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default HttpResponse put(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default HttpResponse put(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default HttpResponse put(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default HttpResponse put(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default HttpResponse patch(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default HttpResponse patch(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default HttpResponse patch(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default HttpResponse patch(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content, charset);
    }

    /**
     * Sends an HTTP DELETE request.
     */
    default HttpResponse delete(String path) {
        return execute(RequestHeaders.of(HttpMethod.DELETE, path));
    }

    /**
     * Sends an HTTP TRACE request.
     */
    default HttpResponse trace(String path) {
        return execute(RequestHeaders.of(HttpMethod.TRACE, path));
    }
}
