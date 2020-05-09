/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

final class RequestContextAwarePromise<T> implements Promise<T> {

    private final RequestContext context;
    private final Promise<T> delegate;

    RequestContextAwarePromise(RequestContext context, Promise<T> delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    @Override
    public Promise<T> setSuccess(T result) {
        delegate.setSuccess(result);
        return this;
    }

    @Override
    public boolean trySuccess(T result) {
        return delegate.trySuccess(result);
    }

    @Override
    public Promise<T> setFailure(Throwable cause) {
        delegate.setFailure(cause);
        return this;
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        return delegate.tryFailure(cause);
    }

    @Override
    public boolean setUncancellable() {
        return delegate.setUncancellable();
    }

    @Override
    public Promise<T> addListener(
            GenericFutureListener<? extends Future<? super T>> listener) {
        delegate.addListener(RequestContextAwareFutureListener.of(context, listener));
        return this;
    }

    @Override
    @SafeVarargs
    public final Promise<T> addListeners(
            GenericFutureListener<? extends Future<? super T>>... listeners) {
        for (GenericFutureListener<? extends Future<? super T>> l : listeners) {
            delegate.addListeners(RequestContextAwareFutureListener.of(context, l));
        }
        return this;
    }

    @Override
    public Promise<T> removeListener(
            GenericFutureListener<? extends Future<? super T>> listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SafeVarargs
    public final Promise<T> removeListeners(
            GenericFutureListener<? extends Future<? super T>>... listeners) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Promise<T> sync() throws InterruptedException {
        delegate.sync();
        return this;
    }

    @Override
    public Promise<T> syncUninterruptibly() {
        delegate.syncUninterruptibly();
        return this;
    }

    @Override
    public boolean isSuccess() {
        return delegate.isSuccess();
    }

    @Override
    public boolean isCancellable() {
        return delegate.isCancellable();
    }

    @Override
    public Throwable cause() {
        return delegate.cause();
    }

    @Override
    public Promise<T> await() throws InterruptedException {
        delegate.await();
        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.await(timeout, unit);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return delegate.await(timeoutMillis);
    }

    @Override
    public Promise<T> awaitUninterruptibly() {
        delegate.awaitUninterruptibly();
        return this;
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        return delegate.awaitUninterruptibly(timeout, unit);
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        return delegate.awaitUninterruptibly(timeoutMillis);
    }

    @Override
    public T getNow() {
        return delegate.getNow();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return delegate.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
        return delegate.isDone();
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        return delegate.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
                                                     java.util.concurrent.TimeoutException {
        return delegate.get(timeout, unit);
    }
}
