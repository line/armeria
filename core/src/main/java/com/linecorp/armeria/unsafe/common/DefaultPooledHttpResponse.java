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

package com.linecorp.armeria.unsafe.common;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.google.common.collect.ObjectArrays;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

final class DefaultPooledHttpResponse extends FilteredHttpResponse implements PooledHttpResponse {

    DefaultPooledHttpResponse(HttpResponse delegate) {
        super(delegate, true);
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (!(obj instanceof HttpData)) {
            return obj;
        }
        return ByteBufHttpData.convert((HttpData) obj);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber) {
        subscribe(subscriber, SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, SubscriptionOption... options) {
        if (hasPooledObjects(options)) {
            super.subscribe(subscriber,  options);
        } else {
            super.subscribe(subscriber, withPooled(options));
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        if (hasPooledObjects(options)) {
            super.subscribe(subscriber, executor, options);
        } else {
            super.subscribe(subscriber, executor, withPooled(options));
        }
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll() {
        return drainAll(SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(SubscriptionOption... options) {
        if (hasPooledObjects(options)) {
            return super.drainAll(options);
        }
        return super.drainAll(withPooled(options));
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(EventExecutor executor) {
        return drainAll(executor, SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public CompletableFuture<List<HttpObject>> drainAll(EventExecutor executor, SubscriptionOption... options) {
        if (hasPooledObjects(options)) {
            return super.drainAll(executor, options);
        }
        return super.drainAll(executor, withPooled(options));
    }

    private boolean hasPooledObjects(SubscriptionOption... options) {
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                return true;
            }
        }
        return false;
    }

    private SubscriptionOption[] withPooled(SubscriptionOption[] options) {
        return ObjectArrays.concat(options, SubscriptionOption.WITH_POOLED_OBJECTS);
    }
}
