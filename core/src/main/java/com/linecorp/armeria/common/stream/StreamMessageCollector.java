/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.unsafe.PooledObjects;

final class StreamMessageCollector<T> implements Subscriber<T> {

    private final CompletableFuture<List<T>> future = new CompletableFuture<>();
    private final boolean withPooledObjects;

    @Nullable
    private ImmutableList.Builder<T> elementsBuilder = ImmutableList.builder();

    StreamMessageCollector(SubscriptionOption[] options) {
        withPooledObjects = containsWithPooledObjects(options);
    }

    public CompletableFuture<List<T>> collect() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T o) {
        requireNonNull(o, "o");

        final T published;
        if (withPooledObjects) {
            published = PooledObjects.touch(o);
        } else {
            published = PooledObjects.copyAndClose(o);
        }
        elementsBuilder.add(published);
    }

    @Override
    public void onComplete() {
        if (future.isDone()) {
            return;
        }
        future.complete(elementsBuilder.build());
        elementsBuilder = null;
    }

    @Override
    public void onError(Throwable t) {
        if (future.isDone()) {
            return;
        }
        final ImmutableList<T> elements = elementsBuilder.build();
        for (final T element : elements) {
            StreamMessageUtil.closeOrAbort(element, t);
        }
        future.completeExceptionally(t);
        elementsBuilder = null;
    }
}
