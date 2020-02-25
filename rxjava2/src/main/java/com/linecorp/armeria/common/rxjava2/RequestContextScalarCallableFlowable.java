/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.common.rxjava2;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.Flowable;
import io.reactivex.internal.fuseable.ConditionalSubscriber;
import io.reactivex.internal.fuseable.ScalarCallable;

final class RequestContextScalarCallableFlowable<T> extends Flowable<T>
        implements ScalarCallable<T> {
    private final Publisher<T> source;
    private final RequestContext assemblyContext;

    RequestContextScalarCallableFlowable(Publisher<T> source, RequestContext assemblyContext) {
        this.source = source;
        this.assemblyContext = assemblyContext;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void subscribeActual(Subscriber<? super T> s) {
        try (SafeCloseable ignored = assemblyContext.push()) {
            if (s instanceof ConditionalSubscriber) {
                source.subscribe(new RequestContextConditionalSubscriber<>(
                        (ConditionalSubscriber<? super T>) s, assemblyContext
                ));
            } else {
                source.subscribe(new RequestContextSubscriber<>(s, assemblyContext));
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public T call() {
        return ((ScalarCallable<T>) source).call();
    }
}
