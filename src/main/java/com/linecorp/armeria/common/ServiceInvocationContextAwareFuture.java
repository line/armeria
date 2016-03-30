package com.linecorp.armeria.common;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

final class ServiceInvocationContextAwareFuture<T> implements Future<T> {

    private final ServiceInvocationContext context;
    private final Future<T> delegate;

    ServiceInvocationContextAwareFuture(ServiceInvocationContext context, Future<T> delegate) {
        this.context = context;
        this.delegate = delegate;
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
    public Future<T> addListener(
            GenericFutureListener<? extends Future<? super T>> listener) {
        return delegate.addListener(context.makeContextAware(listener));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Future<T> addListeners(
            GenericFutureListener<? extends Future<? super T>>... listeners) {
        return delegate.addListeners(Stream.of(listeners)
                                           .map(context::makeContextAware)
                                           .toArray(GenericFutureListener[]::new));
    }

    @Override
    public Future<T> removeListener(
            GenericFutureListener<? extends Future<? super T>> listener) {
        return delegate.removeListener(listener);
    }

    @Override
    @SafeVarargs
    public final Future<T> removeListeners(
            GenericFutureListener<? extends Future<? super T>>... listeners) {
        return delegate.removeListeners(listeners);
    }

    @Override
    public Future<T> sync() throws InterruptedException {
        return delegate.sync();
    }

    @Override
    public Future<T> syncUninterruptibly() {
        return delegate.syncUninterruptibly();
    }

    @Override
    public Future<T> await() throws InterruptedException {
        return delegate.await();
    }

    @Override
    public Future<T> awaitUninterruptibly() {
        return delegate.awaitUninterruptibly();
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
