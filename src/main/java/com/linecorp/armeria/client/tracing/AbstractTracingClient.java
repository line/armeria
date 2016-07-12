/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.tracing;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.ClientRequestAdapter;
import com.github.kristofa.brave.ClientResponseAdapter;
import com.github.kristofa.brave.KeyValueAnnotation;
import com.github.kristofa.brave.SpanId;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * An abstract {@link DecoratingClient} that traces remote service invocations.
 * <p>
 * This class depends on <a href="https://github.com/openzipkin/brave">Brave</a> distributed tracing library.
 */
public abstract class AbstractTracingClient<I extends Request, O extends Response>
        extends DecoratingClient<I, O, I, O> {

    private final ClientTracingInterceptor clientInterceptor;

    protected AbstractTracingClient(Client<? super I, ? extends O> delegate, Brave brave) {
        super(delegate);
        clientInterceptor = new ClientTracingInterceptor(brave);
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        // create new request adapter to catch generated spanId
        final String method = req instanceof RpcRequest ? ((RpcRequest) req).method() : ctx.method();
        final InternalClientRequestAdapter requestAdapter =
                new InternalClientRequestAdapter(Endpoint.create(ctx.endpoint().authority(), 0, 0), method);

        final Span span = clientInterceptor.openSpan(requestAdapter);

        // new client options with trace data
        putTraceData(ctx, req, requestAdapter.getSpanId());

        if (span == null) {
            // skip tracing
            return delegate().execute(ctx, req);
        }

        // The actual remote invocation is done asynchronously.
        // So we have to clear the span from current thread.
        clientInterceptor.clearSpan();

        final O res = delegate().execute(ctx, req);

        ctx.requestLogFuture().thenAcceptBoth(
                res.closeFuture(),
                (log, unused) -> clientInterceptor.closeSpan(span, createResponseAdapter(ctx, log, res)))
           .exceptionally(CompletionActions::log);

        return res;
    }

    /**
     * Puts trace data into the specified base {@link ClientOptions}, returning new instance of
     * {@link ClientOptions}.
     */
    protected abstract void putTraceData(ClientRequestContext ctx, Request req, @Nullable SpanId spanId);

    /**
     * Returns client side annotations that should be added to span.
     */
    @SuppressWarnings("UnusedParameters")
    protected List<KeyValueAnnotation> annotations(ClientRequestContext ctx, RequestLog req, O res) {

        final KeyValueAnnotation clientUriAnnotation = KeyValueAnnotation.create(
                "client.uri", req.scheme().uriText() + "://" + req.host() + ctx.path() + '#' + req.method());

        final CompletableFuture<?> f = res.closeFuture();
        if (!f.isDone()) {
            return Collections.singletonList(clientUriAnnotation);
        }

        final List<KeyValueAnnotation> annotations = new ArrayList<>(3);
        annotations.add(clientUriAnnotation);

        // Need to use a callback because CompletableFuture does not have a getter for the cause of failure.
        // The callback will be invoked immediately because the future is done already.
        f.handle(voidFunction((result, cause) -> {
            final String clientResultText = cause == null ? "success" : "failure";
            annotations.add(KeyValueAnnotation.create("client.result", clientResultText));

            if (cause != null) {
                annotations.add(KeyValueAnnotation.create("client.cause", cause.toString()));
            }
        })).exceptionally(CompletionActions::log);

        return annotations;
    }

    protected ClientResponseAdapter createResponseAdapter(
            ClientRequestContext ctx, RequestLog req, O res) {

        final List<KeyValueAnnotation> annotations = annotations(ctx, req, res);
        return () -> annotations;
    }

    /**
     * A {@link ClientRequestAdapter} holding a {@link SpanId} that was passed from brave.
     */
    private static class InternalClientRequestAdapter implements ClientRequestAdapter {

        private final Endpoint endpoint;
        private final String spanName;

        private SpanId spanId;

        InternalClientRequestAdapter(Endpoint endpoint, String spanName) {
            this.endpoint = endpoint;
            this.spanName = spanName;
        }

        @Override
        public Endpoint serverAddress() {
            return endpoint;
        }

        @Nullable
        public SpanId getSpanId() {
            return spanId;
        }

        @Override
        public String getSpanName() {
            return spanName;
        }

        @Override
        public void addSpanIdToRequest(@Nullable SpanId spanId) {
            this.spanId = spanId;
        }

        @Override
        public Collection<KeyValueAnnotation> requestAnnotations() {
            return Collections.emptyList();
        }
    }

}
