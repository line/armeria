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

import io.reactivex.Maybe;
import io.reactivex.MaybeObserver;
import io.reactivex.MaybeSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.disposables.DisposableHelper;

class RequestContextMaybe<T> extends Maybe<T> {

    final MaybeSource<T> source;
    final RequestContext assemblyContext;

    RequestContextMaybe(MaybeSource<T> source, RequestContext assemblyContext) {
        this.source = source;
        this.assemblyContext = assemblyContext;
    }

    @Override
    protected void subscribeActual(MaybeObserver<? super T> s) {
        try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
            source.subscribe(new RequestContextMaybeObserver<>(s, assemblyContext));
        }
    }

    static final class RequestContextMaybeObserver<T> implements MaybeObserver<T>, Disposable {
        final MaybeObserver<T> actual;
        final RequestContext assemblyContext;
        Disposable disposable;

        RequestContextMaybeObserver(MaybeObserver<T> actual, RequestContext assemblyContext) {
            this.actual = actual;
            this.assemblyContext = assemblyContext;
        }

        @Override
        public void onSubscribe(Disposable d) {
            if (!DisposableHelper.validate(disposable, d)) {
                return;
            }
            disposable = d;
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onError(Throwable t) {
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onError(t);
            }
        }

        @Override
        public void onSuccess(T value) {
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onSuccess(value);
            }
        }

        @Override
        public void onComplete() {
            try (SafeCloseable ignored = RequestContext.push(assemblyContext)) {
                actual.onComplete();
            }
        }

        @Override
        public boolean isDisposed() {
            return disposable.isDisposed();
        }

        @Override
        public void dispose() {
            disposable.dispose();
        }
    }
}
