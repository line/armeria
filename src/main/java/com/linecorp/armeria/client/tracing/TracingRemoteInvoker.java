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

import java.lang.reflect.Method;
import java.net.URI;
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
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingRemoteInvoker;
import com.linecorp.armeria.client.RemoteInvoker;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

/**
 * An abstract {@link RemoteInvoker} that traces remote service invocations.
 * <p>
 * This class depends on <a href="https://github.com/openzipkin/brave">Brave</a> distributed tracing library.
 */
public abstract class TracingRemoteInvoker extends DecoratingRemoteInvoker {

    private final ClientTracingInterceptor clientInterceptor;

    protected TracingRemoteInvoker(RemoteInvoker remoteInvoker, Brave brave) {
        super(remoteInvoker);
        clientInterceptor = new ClientTracingInterceptor(brave);
    }

    @Override
    public final <T> Future<T> invoke(EventLoop eventLoop,  URI uri, ClientOptions options, ClientCodec codec,
                                      Method method, Object[] args) throws Exception {

        // create new request adapter to catch generated spanId
        final InternalClientRequestAdapter requestAdapter = new InternalClientRequestAdapter(method.getName());

        final Span span = clientInterceptor.openSpan(requestAdapter);

        // new client options with trace data
        final ClientOptions traceAwareOptions = putTraceData(options, requestAdapter.getSpanId());

        if (span == null) {
            // skip tracing
            return super.invoke(eventLoop, uri, traceAwareOptions, codec, method, args);
        }

        // The actual remote invocation is done asynchronously.
        // So we have to clear the span from current thread.
        clientInterceptor.clearSpan();

        Future<T> result = null;
        try {
            result = super.invoke(eventLoop, uri, traceAwareOptions, codec, method, args);
            result.addListener(
                    future -> clientInterceptor.closeSpan(span,
                                                          createResponseAdapter(uri, options, codec,
                                                                                method, args, future)));
        } finally {
            if (result == null) {
                clientInterceptor.closeSpan(span,
                                            createResponseAdapter(uri, options, codec, method, args, null));
            }
        }
        return result;
    }

    /**
     * Puts trace data into the specified base {@link ClientOptions}, returning new instance of
     * {@link ClientOptions}.
     */
    protected abstract ClientOptions putTraceData(ClientOptions baseOptions, @Nullable SpanId spanId);

    /**
     * Returns client side annotations that should be added to span.
     */
    @SuppressWarnings("UnusedParameters")
    protected <T> List<KeyValueAnnotation> annotations(URI uri, ClientOptions options, ClientCodec codec,
                                                       Method method, Object[] args,
                                                       @Nullable Future<? super T> result) {

        final KeyValueAnnotation clientUriAnnotation = KeyValueAnnotation.create(
                "client.uri", uri.toString() + '#' + method.getName());

        if (result == null || !result.isDone()) {
            return Collections.singletonList(clientUriAnnotation);
        }

        final List<KeyValueAnnotation> annotations = new ArrayList<>(3);
        annotations.add(clientUriAnnotation);

        final String clientResultText = result.isSuccess() ? "success" : "failure";
        annotations.add(KeyValueAnnotation.create("client.result", clientResultText));

        if (result.cause() != null) {
            annotations.add(KeyValueAnnotation.create("client.cause", result.cause().getMessage()));
        }
        return annotations;
    }

    protected <T> ClientResponseAdapter createResponseAdapter(URI uri, ClientOptions options, ClientCodec codec,
                                                              Method method, Object[] args,
                                                              @Nullable Future<? super T> result) {

        final List<KeyValueAnnotation> annotations = annotations(uri, options, codec, method, args, result);
        return () -> annotations;
    }

    /**
     * A {@link ClientRequestAdapter} holding a {@link SpanId} that was passed from brave.
     */
    private static class InternalClientRequestAdapter implements ClientRequestAdapter {

        private final String spanName;

        private SpanId spanId;

        InternalClientRequestAdapter(String spanName) {
            this.spanName = spanName;
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

        @Override
        public String getClientServiceName() {
            return null;
        }

    }

}
