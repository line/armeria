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

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.reactivex.rxjava3.operators.ConditionalSubscriber;
import io.reactivex.rxjava3.parallel.ParallelFlowable;

final class RequestContextParallelFlowable<T> extends ParallelFlowable<T> {

    private final ParallelFlowable<T> source;
    private final RequestContext assemblyContext;

    RequestContextParallelFlowable(ParallelFlowable<T> source, RequestContext assemblyContext) {
        this.source = source;
        this.assemblyContext = assemblyContext;
    }

    @Override
    public int parallelism() {
        return source.parallelism();
    }

    @Override
    public void subscribe(Subscriber<? super T>[] s) {
        if (!validate(s)) {
            return;
        }
        final int n = s.length;
        @SuppressWarnings("unchecked")
        final Subscriber<? super T>[] parents = new Subscriber[n];
        for (int i = 0; i < n; i++) {
            final Subscriber<? super T> z = s[i];
            if (z instanceof ConditionalSubscriber) {
                parents[i] = new RequestContextConditionalSubscriber<>((ConditionalSubscriber<? super T>) z,
                                                                       assemblyContext);
            } else {
                parents[i] = new RequestContextSubscriber<>(z, assemblyContext);
            }
        }
        try (SafeCloseable ignored = assemblyContext.push()) {
            source.subscribe(parents);
        }
    }
}
