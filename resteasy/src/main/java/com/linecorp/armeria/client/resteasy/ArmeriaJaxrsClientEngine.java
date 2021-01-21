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

package com.linecorp.armeria.client.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.ResponseProcessingException;
import javax.ws.rs.core.Response;

import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.resteasy.ByteBufferBackedOutputStream;
import com.linecorp.armeria.internal.common.resteasy.HttpMessageStream;

import io.netty.buffer.Unpooled;

/**
 * An implementation of {@link AsyncClientHttpEngine} based on Armeria {@link WebClient}.
 * This provides the main entry point for JAX-RS client-side based on Armeria.
 */
@UnstableApi
public class ArmeriaJaxrsClientEngine implements AsyncClientHttpEngine, Closeable {

    static final int DEFAULT_BUFFER_SIZE = 1024;

    private final WebClient client;
    private final int bufferSize;
    @Nullable
    private final Duration readTimeout;

    /**
     * Constructs {@link ArmeriaJaxrsClientEngine} based on {@link WebClient} and other parameters.
     * @param client {@link WebClient} instance to be used by this {@link AsyncClientHttpEngine} to facilitate
     *               HTTP communications.
     * @param bufferSize the size of the buffer used to handle HTTP data output streams, this matches the value
     *                   previously set to {@link ResteasyClientBuilder#responseBufferSize(int)}
     * @param readTimeout response read timeout previously set to
     *                    {@link ResteasyClientBuilder#readTimeout(long, TimeUnit)}
     */
    public ArmeriaJaxrsClientEngine(WebClient client, int bufferSize, @Nullable Duration readTimeout) {
        this.client = requireNonNull(client, "client");
        checkArgument(bufferSize > 0, "bufferSize: %s (expected: > 0)", bufferSize);
        this.bufferSize = bufferSize;
        if (readTimeout != null) {
            checkArgument(!readTimeout.isNegative() && !readTimeout.isZero(),
                          "readTimeout: %s (expected: > 0)", readTimeout);
        }
        this.readTimeout = readTimeout;
    }

    /**
     * Constructs {@link ArmeriaJaxrsClientEngine} based on {@link WebClient}.
     * @param client {@link WebClient} instance to be used by this {@link AsyncClientHttpEngine} to facilitate
     *               HTTP communications.
     */
    public ArmeriaJaxrsClientEngine(WebClient client) {
        this(client, DEFAULT_BUFFER_SIZE, null);
    }

    @Override
    public void close() {
    }

    /**
     * Armeria does not allow to access the ssl-context from WebClient API.
     * This functionality must be achieved by configuring {@link com.linecorp.armeria.client.WebClientBuilder}.
     * @throws UnsupportedOperationException always
     */
    @Override
    @Nullable
    public SSLContext getSslContext() {
        throw new UnsupportedOperationException("getSslContext");
    }

    /**
     * Armeria does not allow to access the HostnameVerifier from WebClient API.
     * This functionality must be achieved by configuring {@link com.linecorp.armeria.client.WebClientBuilder}.
     * @throws UnsupportedOperationException always
     */
    @Override
    @Nullable
    public HostnameVerifier getHostnameVerifier() {
        throw new UnsupportedOperationException("getHostnameVerifier");
    }

    @Override
    public Response invoke(Invocation request) {
        final Future<ClientResponse> future = submit((ClientInvocation) request, false, null,
                                                     response -> response);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            throw clientException(e, null);
        } catch (ExecutionException e) {
            throw clientException(e.getCause(), null);
        }
    }

    @Override
    public <T> Future<T> submit(ClientInvocation request, boolean buffered,
                                @Nullable InvocationCallback<T> callback, ResultExtractor<T> extractor) {

        return submit(request, buffered, extractor, null)
                .whenComplete((response, throwable) -> {
                    if (callback != null) {
                        if (throwable != null) {
                            callback.failed(throwable);
                        } else {
                            callback.completed(response);
                        }
                    }
                });
    }

    @Override
    public <T> CompletableFuture<T> submit(ClientInvocation request, boolean buffered,
                                           ResultExtractor<T> extractor,
                                           @Nullable ExecutorService executorService) {
        final CompletableFuture<HttpResponse> asyncResponseFuture =
                executorService == null ? CompletableFuture.completedFuture(makeAsyncRequest(request, buffered))
                                        : CompletableFuture.supplyAsync(
                                                () -> makeAsyncRequest(request, buffered), executorService);
        return asyncResponseFuture.thenCompose(asyncResponse ->
                                                       handleAsyncResponse(request.getClientConfiguration(),
                                                                           asyncResponse, buffered, extractor));
    }

    private HttpResponse makeAsyncRequest(ClientInvocation request, boolean buffered) {
        // compose RequestHeaders
        final HttpMethod method = HttpMethod.valueOf(request.getMethod());
        final URI requestUri = request.getUri();
        final String path = getServicePath(requestUri);
        final RequestHeadersBuilder requestHeadersBuilder = RequestHeaders.builder()
                                                                          .method(method)
                                                                          .scheme(requestUri.getScheme())
                                                                          .authority(requestUri.getAuthority())
                                                                          .path(path);
        // copy request headers from ClientInvocation
        request.getHeaders().getHeaders().forEach(requestHeadersBuilder::addObject);
        final RequestHeaders requestHeaders = requestHeadersBuilder.build();

        if (request.getEntity() == null) {
            // if there is no body - execute request and return response
            return client.execute(HttpRequest.of(requestHeaders));
        }

        if (buffered) {
            // Request + Response fully buffered in memory. Future signalled after the entire response
            // received and processed.
            final HttpRequest asyncRequest;
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                request.getDelegatingOutputStream().setDelegate(os);
                request.writeRequestBody(request.getEntityStream());
                asyncRequest = HttpRequest.of(requestHeaders, HttpData.wrap(os.toByteArray()));
            } catch (IOException e) {
                throw new RuntimeException("Failed to write the request body!", e);
            }
            return client.execute(asyncRequest);
        } else {
            // Unbuffered: Future returns immediately after headers. Reading the response-stream blocks,
            // but one may check InputStream#available() to prevent blocking.
            final HttpRequestWriter requestWriter = HttpRequest.streaming(requestHeaders);
            final HttpResponse asyncResponse = client.execute(requestWriter);
            final ByteBufferBackedOutputStream requestContentStream
                    = new ByteBufferBackedOutputStream(bufferSize, buff -> {
                        if (buff.hasRemaining()) {
                            requestWriter.write(HttpData.wrap(Unpooled.wrappedBuffer(buff)));
                        }
                    });
            request.getDelegatingOutputStream().setDelegate(requestContentStream);
            try {
                request.writeRequestBody(request.getEntityStream());
                requestContentStream.close();
            } catch (IOException e) {
                requestWriter.close(e);
                throw new RuntimeException("Failed to write the request body!", e);
            }
            requestWriter.close();
            return asyncResponse;
        }
    }

    private <T> CompletableFuture<T> handleAsyncResponse(ClientConfiguration clientConfiguration,
                                                         HttpResponse asyncResponse,
                                                         boolean buffered,
                                                         ResultExtractor<T> extractor) {
        if (buffered) {
            // Request + Response fully buffered in memory. Future signalled after the entire response
            // received and processed.
            final CompletableFuture<AggregatedHttpResponse> responseFuture = asyncResponse.aggregate();
            return responseFuture.thenApply(aggregatedResponse -> {
                final HttpData responseContent = aggregatedResponse.content();
                final ClientResponse response = new ResteasyClientResponseImpl(clientConfiguration,
                                                                               aggregatedResponse.headers(),
                                                                               responseContent.toInputStream());
                return extractor.extractResult(response);
            });
        } else {
            // Unbuffered: Future returns immediately after headers. Reading the response-stream blocks,
            // but one may check InputStream#available() to prevent blocking.
            final HttpMessageStream messageStream =
                    readTimeout == null ? HttpMessageStream.of(asyncResponse)
                                        : HttpMessageStream.of(asyncResponse, readTimeout);
            return messageStream.awaitHeaders()
                                .thenApply(asyncResponseHeaders -> {
                                    final ClientResponse response =
                                            new ResteasyClientResponseImpl(clientConfiguration,
                                                                           asyncResponseHeaders,
                                                                           messageStream.content());
                                    return extractor.extractResult(response);
                                });
        }
    }

    private static RuntimeException clientException(@Nullable Throwable ex,
                                                    @Nullable Response clientResponse) {

        final RuntimeException ret;
        if (ex == null) {
            ret = new ProcessingException(new NullPointerException());
        } else if (ex instanceof WebApplicationException) {
            ret = (WebApplicationException) ex;
        } else if (ex instanceof ProcessingException) {
            ret = (ProcessingException) ex;
        } else if (clientResponse != null) {
            ret = new ResponseProcessingException(clientResponse, ex);
        } else {
            ret = new ProcessingException(ex);
        }
        return ret;
    }

    /**
     * Extracts path, query and fragment portions of the {@link URI}.
     */
    private static String getServicePath(final URI uri) {
        final StringBuilder builder = new StringBuilder();
        builder.append(requireNonNull(uri.getRawPath(), "path"));
        final String query = uri.getRawQuery();
        if (query != null) {
            builder.append('?').append(query);
        }
        final String fragment = uri.getRawFragment();
        if (fragment != null) {
            builder.append('#').append(fragment);
        }
        return builder.toString();
    }
}
