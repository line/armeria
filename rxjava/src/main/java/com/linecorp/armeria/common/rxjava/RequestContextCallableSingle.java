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

import java.util.concurrent.Callable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.SingleSource;

final class RequestContextCallableSingle<T> extends Single<T> implements Callable<T> {
    private final SingleSource<T> source;
    private final RequestContext assemblyContext;

    RequestContextCallableSingle(SingleSource<T> source, RequestContext assemblyContext) {
        this.source = source;
        this.assemblyContext = assemblyContext;
    }

    @Override
    protected void subscribeActual(SingleObserver<? super T> s) {
        try (SafeCloseable ignored = assemblyContext.pushIfAbsent()) {
            source.subscribe(new RequestContextSingleObserver<>(s, assemblyContext));
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public T call() throws Exception {
        try (SafeCloseable ignored = assemblyContext.pushIfAbsent()) {
            return ((Callable<T>) source).call();
        }
    }
}
