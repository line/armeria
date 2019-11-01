/*
 * Copyright 2016 LINE Corporation
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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

class AbstractClientOptionsBuilder<B extends AbstractClientOptionsBuilder<B>> {

    private final Map<ClientOption<?>, ClientOptionValue<?>> options = new LinkedHashMap<>();
    private final ClientDecorationBuilder decoration = ClientDecoration.builder();
    private final HttpHeadersBuilder httpHeaders = HttpHeaders.builder();

    /**
     * Creates a new instance with the default options.
     */
    AbstractClientOptionsBuilder() {}

    /**
     * Creates a new instance with the specified base options.
     */
    AbstractClientOptionsBuilder(ClientOptions options) {
        requireNonNull(options, "options");
        options(options);
    }

    @SuppressWarnings("unchecked")
    final B self() {
        return (B) this;
    }

    /**
     * Adds the specified {@link ClientOptions}.
     */
    public B options(ClientOptions options) {
        requireNonNull(options, "options");
        options.asMap().values().forEach(this::option);
        return self();
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public B options(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        for (ClientOptionValue<?> o : options) {
            option(o);
        }
        return self();
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public B options(Iterable<ClientOptionValue<?>> options) {
        requireNonNull(options, "options");
        for (ClientOptionValue<?> o : options) {
            option(o);
        }
        return self();
    }

    /**
     * Adds the specified {@link ClientOption} and its {@code value}.
     */
    public <T> B option(ClientOption<T> option, T value) {
        requireNonNull(option, "option");
        requireNonNull(value, "value");
        return option(option.newValue(value));
    }

    /**
     * Adds the specified {@link ClientOptionValue}.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> B option(ClientOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");
        final ClientOption<?> opt = optionValue.option();
        if (opt == ClientOption.DECORATION) {
            final ClientDecoration d = (ClientDecoration) optionValue.value();
            d.entries().forEach(e -> decoration.add0(e.requestType(), e.responseType(),
                                                     (Function) e.decorator()));
        } else if (opt == ClientOption.HTTP_HEADERS) {
            final HttpHeaders h = (HttpHeaders) optionValue.value();
            setHttpHeaders(h);
        } else {
            options.put(opt, optionValue);
        }
        return self();
    }

    /**
     * Sets the timeout of a socket write attempt.
     *
     * @deprecated Use {@link #writeTimeout(Duration)}.
     *
     * @param writeTimeout the timeout. {@code 0} disables the timeout.
     */
    @Deprecated
    public B defaultWriteTimeout(Duration writeTimeout) {
        return writeTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    /**
     * Sets the timeout of a socket write attempt in milliseconds.
     *
     * @deprecated Use {@link #writeTimeoutMillis(long)}.
     *
     * @param writeTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    @Deprecated
    public B defaultWriteTimeoutMillis(long writeTimeoutMillis) {
        return writeTimeoutMillis(writeTimeoutMillis);
    }

    /**
     * Sets the timeout of a socket write attempt.
     *
     * @param writeTimeout the timeout. {@code 0} disables the timeout.
     */
    public B writeTimeout(Duration writeTimeout) {
        return writeTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    /**
     * Sets the timeout of a socket write attempt in milliseconds.
     *
     * @param writeTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public B writeTimeoutMillis(long writeTimeoutMillis) {
        return option(ClientOption.WRITE_TIMEOUT_MILLIS, writeTimeoutMillis);
    }

    /**
     * Sets the timeout of a response.
     *
     * @deprecated Use {@link #responseTimeout(Duration)}.
     *
     * @param responseTimeout the timeout. {@code 0} disables the timeout.
     */
    @Deprecated
    public B defaultResponseTimeout(Duration responseTimeout) {
        return responseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    /**
     * Sets the timeout of a response in milliseconds.
     *
     * @deprecated Use {@link #responseTimeoutMillis(long)}.
     *
     * @param responseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    @Deprecated
    public B defaultResponseTimeoutMillis(long responseTimeoutMillis) {
        return responseTimeoutMillis(responseTimeoutMillis);
    }

    /**
     * Sets the timeout of a response.
     *
     * @param responseTimeout the timeout. {@code 0} disables the timeout.
     */
    public B responseTimeout(Duration responseTimeout) {
        return responseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    /**
     * Sets the timeout of a response in milliseconds.
     *
     * @param responseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public B responseTimeoutMillis(long responseTimeoutMillis) {
        return option(ClientOption.RESPONSE_TIMEOUT_MILLIS, responseTimeoutMillis);
    }

    /**
     * Sets the maximum allowed length of a server response in bytes.
     *
     * @deprecated Use {@link #maxResponseLength(long)}.
     *
     * @param maxResponseLength the maximum length in bytes. {@code 0} disables the limit.
     */
    @Deprecated
    public B defaultMaxResponseLength(long maxResponseLength) {
        return maxResponseLength(maxResponseLength);
    }

    /**
     * Sets the maximum allowed length of a server response in bytes.
     *
     * @param maxResponseLength the maximum length in bytes. {@code 0} disables the limit.
     */
    public B maxResponseLength(long maxResponseLength) {
        return option(ClientOption.MAX_RESPONSE_LENGTH, maxResponseLength);
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request.
     */
    public B requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        return option(ClientOption.REQ_CONTENT_PREVIEWER_FACTORY,
                      requireNonNull(factory, "factory"));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a response.
     */
    public B responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        return option(ClientOption.RES_CONTENT_PREVIEWER_FACTORY,
                      requireNonNull(factory, "factory"));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request and a response.
     */
    public B contentPreviewerFactory(ContentPreviewerFactory factory) {
        requireNonNull(factory, "factory");
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return self();
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for a request and a response.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following conditions:
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
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for a request and a response.
     * The previewer is enabled only if the content type of a request/response meets
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
     * Sets the {@link Supplier} that generates a {@link UUID} for each {@link Request}.
     */
    public B uuidGenerator(Supplier<UUID> uuidGenerator) {
       return option(ClientOption.UUID_GENERATOR, uuidGenerator);
    }

    /**
     * Adds the specified {@code decorator}.
     *
     * @param requestType the type of the {@link Request} that the {@code decorator} is interested in
     * @param responseType the type of the {@link Response} that the {@code decorator} is interested in
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     * @param <T> the type of the {@link Client} being decorated
     * @param <R> the type of the {@link Client} produced by the {@code decorator}
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     *
     * @deprecated Use {@link #decorator(Function)} or {@link #rpcDecorator(Function)}.
     */
    @Deprecated
    public <T extends Client<I, O>, R extends Client<I, O>, I extends Request, O extends Response>
    B decorator(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {
        decoration.add(requestType, responseType, decorator);
        return self();
    }

    /**
     * Adds the specified {@code decorator}.
     *
     * @param requestType the type of the {@link Request} that the {@code decorator} is interested in
     * @param responseType the type of the {@link Response} that the {@code decorator} is interested in
     * @param decorator the {@link DecoratingClientFunction} that intercepts an invocation
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     *
     * @deprecated Use {@link #decorator(DecoratingClientFunction)} or
     *             {@link #rpcDecorator(DecoratingClientFunction)}.
     */
    @Deprecated
    public <I extends Request, O extends Response>
    B decorator(Class<I> requestType, Class<O> responseType, DecoratingClientFunction<I, O> decorator) {
        decoration.add(requestType, responseType, decorator);
        return self();
    }

    /**
     * Adds the specified HTTP-level {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     * @param <T> the type of the {@link Client} being decorated
     * @param <R> the type of the {@link Client} produced by the {@code decorator}
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     */
    public <T extends Client<I, O>, R extends Client<I, O>, I extends HttpRequest, O extends HttpResponse>
    B decorator(Function<T, R> decorator) {
        decoration.add(decorator);
        return self();
    }

    /**
     * Adds the specified HTTP-level {@code decorator}.
     *
     * @param decorator the {@link DecoratingClientFunction} that intercepts an invocation
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     */
    public <I extends HttpRequest, O extends HttpResponse>
    B decorator(DecoratingClientFunction<I, O> decorator) {
        decoration.add(decorator);
        return self();
    }

    /**
     * Adds the specified RPC-level {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     * @param <T> the type of the {@link Client} being decorated
     * @param <R> the type of the {@link Client} produced by the {@code decorator}
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     */
    public <T extends Client<I, O>, R extends Client<I, O>, I extends RpcRequest, O extends RpcResponse>
    B rpcDecorator(Function<T, R> decorator) {
        decoration.addRpc(decorator);
        return self();
    }

    /**
     * Adds the specified RPC-level {@code decorator}.
     *
     * @param decorator the {@link DecoratingClientFunction} that intercepts an invocation
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     */
    public <I extends RpcRequest, O extends RpcResponse>
    B rpcDecorator(DecoratingClientFunction<I, O> decorator) {
        decoration.addRpc(decorator);
        return self();
    }

    /**
     * Adds the specified HTTP header.
     */
    public B addHttpHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        httpHeaders.addObject(HttpHeaderNames.of(name), value);
        return self();
    }

    /**
     * Adds the specified HTTP headers.
     */
    public B addHttpHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> httpHeaders) {
        requireNonNull(httpHeaders, "httpHeaders");
        this.httpHeaders.addObject(httpHeaders);
        return self();
    }

    /**
     * Sets the specified HTTP header.
     */
    public B setHttpHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        httpHeaders.setObject(HttpHeaderNames.of(name), value);
        return self();
    }

    /**
     * Sets the specified HTTP headers.
     */
    public B setHttpHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> httpHeaders) {
        requireNonNull(httpHeaders, "httpHeaders");
        this.httpHeaders.setObject(httpHeaders);
        return self();
    }

    ClientOptions buildOptions() {
        final Collection<ClientOptionValue<?>> optVals = options.values();
        final int numOpts = optVals.size();
        final ClientOptionValue<?>[] optValArray = optVals.toArray(new ClientOptionValue[numOpts + 2]);
        optValArray[numOpts] = ClientOption.DECORATION.newValue(decoration.build());
        optValArray[numOpts + 1] = ClientOption.HTTP_HEADERS.newValue(httpHeaders.build());

        return ClientOptions.of(optValArray);
    }
}
