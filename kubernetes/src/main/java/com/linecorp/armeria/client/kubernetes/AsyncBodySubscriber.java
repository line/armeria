/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpData;

import io.fabric8.kubernetes.client.http.AsyncBody;

final class AsyncBodySubscriber implements Subscriber<HttpData>, AsyncBody {
    private final AsyncBody.Consumer<List<ByteBuffer>> consumer;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    // `CompletableFuture` is used instead `volatile` because we don't know when `consume()` is called.
    private final CompletableFuture<Subscription> subscription = new CompletableFuture<>();

    AsyncBodySubscriber(AsyncBody.Consumer<List<ByteBuffer>> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (this.subscription.isDone()) {
            subscription.cancel();
            return;
        }
        this.subscription.complete(subscription);
    }

    @Override
    public void onNext(HttpData item) {
        try {
            consumer.consume(ImmutableList.of(item.byteBuf().nioBuffer()), this);
        } catch (Exception e) {
            subscription.join().cancel();
            done.completeExceptionally(e);
        } finally {
            item.close();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        done.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        done.complete(null);
    }

    @Override
    public void consume() {
        if (done.isDone()) {
            return;
        }
        subscription.thenAccept(s -> s.request(1));
    }

    @Override
    public CompletableFuture<Void> done() {
        return done;
    }

    @Override
    public void cancel() {
        subscription.thenAccept(Subscription::cancel);
        done.cancel(false);
    }
}

