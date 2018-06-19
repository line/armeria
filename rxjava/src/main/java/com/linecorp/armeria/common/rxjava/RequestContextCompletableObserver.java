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

package com.linecorp.armeria.common.rxjava;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.CompletableObserver;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.disposables.DisposableHelper;

final class RequestContextCompletableObserver implements CompletableObserver, Disposable {
    private final CompletableObserver actual;
    private final RequestContext assemblyContext;
    private Disposable disposable;

    RequestContextCompletableObserver(CompletableObserver actual, RequestContext assemblyContext) {
        this.actual = actual;
        this.assemblyContext = assemblyContext;
    }

    @Override
    public void onSubscribe(Disposable d) {
        if (!DisposableHelper.validate(disposable, d)) {
            return;
        }
        disposable = d;
        try (SafeCloseable ignored = assemblyContext.pushIfAbsent()) {
            actual.onSubscribe(this);
        }
    }

    @Override
    public void onError(Throwable t) {
        try (SafeCloseable ignored = assemblyContext.pushIfAbsent()) {
            actual.onError(t);
        }
    }

    @Override
    public void onComplete() {
        try (SafeCloseable ignored = assemblyContext.pushIfAbsent()) {
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
