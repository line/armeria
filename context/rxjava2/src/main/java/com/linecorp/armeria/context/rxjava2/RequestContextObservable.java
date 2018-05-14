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

package com.linecorp.armeria.context.rxjava2;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.internal.fuseable.QueueDisposable;
import io.reactivex.internal.observers.BasicFuseableObserver;

class RequestContextObservable<T> extends Observable<T> {

    final ObservableSource<T> source;
    final RequestContext assemblyContext;

    RequestContextObservable(ObservableSource<T> source, RequestContext assemblyContext) {
        this.source = source;
        this.assemblyContext = assemblyContext;
    }

    @Override
    protected void subscribeActual(Observer<? super T> s) {
        try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
            source.subscribe(new RequestContextObserver<>(s, assemblyContext));
        }
    }

    static final class RequestContextObserver<T> extends BasicFuseableObserver<T, T> {
        final RequestContext assemblyContext;

        RequestContextObserver(Observer<? super T> actual, RequestContext assemblyContext) {
            super(actual);
            this.assemblyContext = assemblyContext;
        }

        @Override
        public void onNext(T t) {
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onNext(t);
            }
        }

        @Override
        public void onError(Throwable t) {
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onError(t);
            }
        }

        @Override
        public void onComplete() {
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onComplete();
            }
        }

        @Override
        public int requestFusion(int mode) {
            QueueDisposable<T> qs = this.qs;
            if (qs != null) {
                int m = qs.requestFusion(mode);
                sourceMode = m;
                return m;
            }
            return NONE;
        }

        @Override
        public T poll() throws Exception {
            return qs.poll();
        }
    }
}
