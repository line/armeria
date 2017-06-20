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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.ClientRequestAdapter;
import com.github.kristofa.brave.ClientResponseAdapter;
import com.github.kristofa.brave.KeyValueAnnotation;
import com.github.kristofa.brave.SpanId;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;

/**
 * An abstract {@link DecoratingClient} that traces outgoing {@link Request}s.
 *
 * <p>This class depends on <a href="https://github.com/openzipkin/brave">Brave</a>, a distributed tracing
 * library.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractTracingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private final ClientTracingInterceptor clientInterceptor;

    /**
     * Creates a new instance.
     */
    protected AbstractTracingClient(Client<I, O> delegate, Brave brave) {
        super(delegate);
        clientInterceptor = new ClientTracingInterceptor(brave);
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        // create new request adapter to catch generated spanId
        final String method = req instanceof RpcRequest ? ((RpcRequest) req).method() : ctx.method().name();
        final InternalClientRequestAdapter requestAdapter =
                new InternalClientRequestAdapter(
                        Endpoint.builder()
                                .serviceName(ctx.endpoint().authority())
                                .build(),
                        method);

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

        ctx.log().addListener(log -> closeSpan(ctx, span, log), RequestLogAvailability.COMPLETE);

        return delegate().execute(ctx, req);
    }

    /**
     * Puts trace data into the specified {@link Request} or {@link ClientRequestContext}.
     */
    protected abstract void putTraceData(ClientRequestContext ctx, I req, @Nullable SpanId spanId);

    /**
     * Returns the client-side annotations that should be added to a Zipkin span.
     */
    protected List<KeyValueAnnotation> annotations(ClientRequestContext ctx, RequestLog log) {

        final KeyValueAnnotation clientUriAnnotation = KeyValueAnnotation.create(
                "client.uri", log.scheme().uriText() + "://" + log.host() + ctx.path() + '#' + log.method());

        final List<KeyValueAnnotation> annotations = new ArrayList<>(3);
        annotations.add(clientUriAnnotation);

        final Throwable cause = log.responseCause();
        final String clientResultText = cause == null ? "success" : "failure";
        annotations.add(KeyValueAnnotation.create("client.result", clientResultText));
        if (cause != null) {
            annotations.add(KeyValueAnnotation.create("client.cause", cause.toString()));
        }

        return annotations;
    }

    private void closeSpan(ClientRequestContext ctx, Span span, RequestLog log) {
        final Object requestContent = log.requestContent();
        if (requestContent instanceof RpcRequest) {
            span.setName(((RpcRequest) requestContent).method());
        }
        clientInterceptor.closeSpan(span, createResponseAdapter(ctx, log));
    }

    /**
     * Creates a new {@link ClientResponseAdapter} from the specified request-response information.
     */
    protected ClientResponseAdapter createResponseAdapter(ClientRequestContext ctx, RequestLog req) {

        final List<KeyValueAnnotation> annotations = annotations(ctx, req);
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
