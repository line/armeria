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
import java.util.stream.Stream;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ProgressivePromise;

final class RequestContextAwareProgressivePromise<T> implements ProgressivePromise<T> {

    private final RequestContext context;
    private final ProgressivePromise<T> delegate;

    RequestContextAwareProgressivePromise(RequestContext context, ProgressivePromise<T> delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    @Override
    public ProgressivePromise<T> setProgress(long progress, long total) {
        return delegate.setProgress(progress, total);
    }

    @Override
    public boolean tryProgress(long progress, long total) {
        return delegate.tryProgress(progress, total);
    }

    @Override
    public ProgressivePromise<T> setSuccess(T result) {
        return delegate.setSuccess(result);
    }

    @Override
    public ProgressivePromise<T> setFailure(Throwable cause) {
        return delegate.setFailure(cause);
    }

    @Override
    public ProgressivePromise<T> addListener(
            GenericFutureListener<? extends Future<? super T>> listener) {
        return delegate.addListener(context.makeContextAware(listener));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public ProgressivePromise<T> addListeners(
            GenericFutureListener<? extends Future<? super T>>... listeners) {
        return delegate.addListeners(Stream.of(listeners)
                                           .map(context::makeContextAware)
                                           .toArray(GenericFutureListener[]::new));
    }

    @Override
    public ProgressivePromise<T> removeListener(
            GenericFutureListener<? extends Future<? super T>> listener) {
        return delegate.removeListener(listener);
    }

    @Override
    @SafeVarargs
    public final ProgressivePromise<T> removeListeners(
            GenericFutureListener<? extends Future<? super T>>... listeners) {
        return delegate.removeListeners(listeners);
    }

    @Override
    public ProgressivePromise<T> sync() throws InterruptedException {
        return delegate.sync();
    }

    @Override
    public ProgressivePromise<T> syncUninterruptibly() {
        return delegate.syncUninterruptibly();
    }

    @Override
    public boolean trySuccess(T result) {
        return delegate.trySuccess(result);
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
    public ProgressivePromise<T> await() throws InterruptedException {
        return delegate.await();
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
    public ProgressivePromise<T> awaitUninterruptibly() {
        return delegate.awaitUninterruptibly();
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
