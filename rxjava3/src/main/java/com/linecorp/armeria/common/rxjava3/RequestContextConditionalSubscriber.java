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

import io.reactivex.rxjava3.internal.subscribers.BasicFuseableConditionalSubscriber;
import io.reactivex.rxjava3.operators.ConditionalSubscriber;
import io.reactivex.rxjava3.operators.QueueSubscription;

final class RequestContextConditionalSubscriber<T> extends BasicFuseableConditionalSubscriber<T, T> {

    private final RequestContext assemblyContext;

    RequestContextConditionalSubscriber(ConditionalSubscriber<? super T> downstream,
                                        RequestContext assemblyContext) {
        super(downstream);
        this.assemblyContext = assemblyContext;
    }

    @Override
    public boolean tryOnNext(T t) {
        try (SafeCloseable ignored = assemblyContext.push()) {
            return downstream.tryOnNext(t);
        }
    }

    @Override
    public void onNext(T t) {
        try (SafeCloseable ignored = assemblyContext.push()) {
            downstream.onNext(t);
        }
    }

    @Override
    public void onError(Throwable t) {
        try (SafeCloseable ignored = assemblyContext.push()) {
            downstream.onError(t);
        }
    }

    @Override
    public void onComplete() {
        try (SafeCloseable ignored = assemblyContext.push()) {
            downstream.onComplete();
        }
    }

    @Override
    public int requestFusion(int mode) {
        final QueueSubscription<T> qs = this.qs;
        if (qs != null) {
            final int m = qs.requestFusion(mode);
            sourceMode = m;
            return m;
        }
        return NONE;
    }

    @Override
    public T poll() throws Throwable {
        return qs.poll();
    }
}
