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

package com.linecorp.armeria.common.tracing;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.github.kristofa.brave.SpanCollector;
import com.twitter.zipkin.gen.Span;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

public abstract class TracingTestBase {

    public static class StubCollector implements SpanCollector {

        public final List<Span> spans = new ArrayList<>();

        @Override
        public void collect(Span span) {
            spans.add(span);
        }

        @Override
        public void addDefaultAnnotation(String s, String s1) {
        }
    }

    public static class Service {

        public void serve() {
        }
    }

    public static Method getServiceMethod() throws NoSuchMethodException {
        return Service.class.getMethod("serve");
    }

    @SuppressWarnings("unchecked")
    public static <T> Future<T> mockFuture() {
        Future<T> future = (Future<T>) mock(Future.class);
        when(future.addListener(any())).then(invoc -> {
            GenericFutureListener<Future<T>> listener = invoc.getArgumentAt(0, GenericFutureListener.class);
            listener.operationComplete(future);
            return future;
        });
        return future;
    }

    @SuppressWarnings("unchecked")
    public static <T> Promise<T> mockPromise() {
        Promise<T> promise = (Promise<T>) mock(Promise.class);
        when(promise.addListener(any())).then(invoc -> {
            GenericFutureListener<Future<T>> listener = invoc.getArgumentAt(0, GenericFutureListener.class);
            listener.operationComplete(promise);
            return promise;
        });
        return promise;
    }
}
