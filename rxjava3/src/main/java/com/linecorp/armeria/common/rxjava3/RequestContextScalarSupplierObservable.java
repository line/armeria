/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.rxjava3;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableSource;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.operators.ScalarSupplier;

final class RequestContextScalarSupplierObservable<T> extends Observable<T> implements ScalarSupplier<T> {

    private final ObservableSource<T> source;
    private final RequestContext assemblyContext;

    RequestContextScalarSupplierObservable(ObservableSource<T> source, RequestContext assemblyContext) {
        this.source = source;
        this.assemblyContext = assemblyContext;
    }

    @Override
    protected void subscribeActual(Observer<? super T> s) {
        try (SafeCloseable ignored = assemblyContext.push()) {
            source.subscribe(new RequestContextObserver<>(s, assemblyContext));
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public T get() {
        return ((ScalarSupplier<T>) source).get();
    }
}
