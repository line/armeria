/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import io.netty.util.ReferenceCountUtil;

final class StreamMessageDrainer<T> implements Subscriber<T> {

    private final CompletableFuture<List<T>> future = new CompletableFuture<>();

    private final Builder<T> drained = ImmutableList.builder();

    CompletableFuture<List<T>> future() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T t) {
        drained.add(t);
    }

    @Override
    public void onError(Throwable t) {
        future.completeExceptionally(t);
        drained.build().forEach(ReferenceCountUtil::safeRelease);
    }

    @Override
    public void onComplete() {
        future.complete(drained.build());
    }
}
