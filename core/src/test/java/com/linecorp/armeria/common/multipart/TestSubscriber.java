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
/*
 * Copyright (c) 2018, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.common.multipart;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A dummy subscriber for testing purposes.
 */
class TestSubscriber<T> implements Subscriber<T> {

    // Forked from https://github.com/oracle/helidon/blob/25ffc5088e87e17c981bf37b8ccb7e8c188a6b0f/common/reactive/src/test/java/io/helidon/common/reactive/TestSubscriber.java

    private final long initialRequest;

    private final AtomicReference<Subscription> upstream;

    private final List<T> items;

    private final List<Throwable> errors;

    private final CountDownLatch latch;

    private volatile int completions;

    /**
     * Returns a new {@link TestSubscriber} with no initial request.
     */
    TestSubscriber() {
        this(0);
    }

    /**
     * Returns a new {@link TestSubscriber} with the given non-negative initial request.
     * @param initialRequest the initial request amount, non-negative
     */
    TestSubscriber(long initialRequest) {
        checkArgument(initialRequest >= 0, "initialRequest: %s (expected: >= 0)", initialRequest);
        this.initialRequest = initialRequest;
        upstream = new AtomicReference<>();
        items = Collections.synchronizedList(new ArrayList<>());
        errors = Collections.synchronizedList(new ArrayList<>());
        latch = new CountDownLatch(1);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        if (upstream.compareAndSet(null, subscription)) {
            if (initialRequest != 0) {
                subscription.request(initialRequest);
            }
        } else {
            subscription.cancel();
            if (upstream.get() != SubscriptionHelper.CANCELLED) {
                errors.add(new IllegalStateException("Subscription already set!"));
            }
        }
    }

    @Override
    public void onNext(T item) {
        requireNonNull(item, "item");
        if (upstream.get() == null) {
            errors.add(new IllegalStateException("onSubscribe not called before onNext!"));
        }
        if (!errors.isEmpty()) {
            errors.add(new IllegalStateException("onNext called after onError!"));
        }
        if (completions != 0) {
            errors.add(new IllegalStateException("onNext called after onComplete!"));
        }
        items.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        if (upstream.get() == null) {
            errors.add(new IllegalStateException("onSubscribe not called before onError!"));
        }
        errors.add(throwable);
        latch.countDown();
    }

    @Override
    public void onComplete() {
        if (upstream.get() == null) {
            errors.add(new IllegalStateException("onSubscribe not called before onComplete!"));
        }
        if (!errors.isEmpty()) {
            errors.add(new IllegalStateException("onComplete called when error(s) are present"));
        }
        final int c = completions;
        if (c > 1) {
            errors.add(new IllegalStateException("onComplete called again"));
        }
        completions = c + 1;
        latch.countDown();
    }

    /**
     * Returns a mutable, thread-safe list of items received so far.
     */
    final List<T> getItems() {
        return items;
    }

    /**
     * Returns a mutable, thread-safe list of error(s) encountered so far.
     */
    final List<Throwable> getErrors() {
        return errors;
    }

    /**
     * Returns the last error received by this {@link TestSubscriber} or {@code null} if no error happened.
     */
    @Nullable
    final Throwable getLastError() {
        final int n = errors.size();
        if (n != 0) {
            return errors.get(n - 1);
        }
        return null;
    }

    /**
     * Returns true if this {@link TestSubscriber} received any {@link #onComplete()} calls.
     */
    final boolean isComplete() {
        return completions != 0;
    }

    /**
     * Returns the current upstream {@link Subscription} instance.
     */
    final Subscription subscription() {
        return upstream.get();
    }

    /**
     * Assembles an {@link AssertionError} with the current internal state and captured errors.
     * @param message the extra message to add
     * @return the AssertionError instance
     */
    final AssertionError fail(String message) {
        requireNonNull(message, "message");
        final StringBuilder sb = new StringBuilder();
        sb.append(message)
          .append(" (")
          .append("items: ")
          .append(items.size())
          .append(", errors: ")
          .append(errors.size())
          .append(", completions: ")
          .append(completions);

        if (upstream.get() == null) {
            sb.append(", no onSubscribe!");
        } else if (upstream.get() == SubscriptionHelper.CANCELLED) {
            sb.append(", canceled!");
        }

        sb.append(')');

        final AssertionError ae = new AssertionError(sb.toString());

        for (Throwable error : errors) {
            ae.addSuppressed(error);
        }

        return ae;
    }

    /**
     * Requests more from the upstream.
     * @param n the request amount, positive
     * @throws IllegalArgumentException if {@code n} is non-positive
     * @throws IllegalStateException if {@link #onSubscribe} was not called on this {@link TestSubscriber} yet.
     */
    final TestSubscriber<T> request(long n) {
        checkArgument(n > 0, "n: %s (expected: > 0)", n);
        final Subscription s = upstream.get();
        checkState(s != null, "onSubscribe not called yet!");

        s.request(n);
        return this;
    }

    /**
     * Requests one more item from the upstream.
     * @throws IllegalStateException if {@link #onSubscribe} was not called on this {@link TestSubscriber} yet.
     */
    final TestSubscriber<T> request1() {
        final Subscription s = upstream.get();
        checkState(s != null, "onSubscribe not called yet!");

        s.request(1);
        return this;
    }

    /**
     * Requests {@link Long#MAX_VALUE} items from the upstream.
     * @throws IllegalStateException if {@link #onSubscribe} was not called on this {@link TestSubscriber} yet.
     */
    final TestSubscriber<T> requestMax() {
        final Subscription s = upstream.get();
        checkState(s != null, "onSubscribe not called yet!");
        s.request(Long.MAX_VALUE);
        return this;
    }

    /**
     * Cancels the upstream.
     */
    final TestSubscriber<T> cancel() {
        SubscriptionHelper.cancel(upstream);
        return this;
    }

    /**
     * Turns a value into a value + class name string.
     * @param o the object turn into a string
     */
    private static String valueAndClass(Object o) {
        if (o == null) {
            return "null";
        }
        return o + " (" + o.getClass().getName() + ')';
    }

    /**
     * Asserts that this {@link TestSubscriber} received the exactly the expected items
     * in the expected order.
     * @param expectedItems the vararg array of the expected items
     * @throws AssertionError if the number of items or the items themselves are not equal to the expected items
     */
    @SafeVarargs
    final TestSubscriber<T> assertValues(T... expectedItems) {
        final int n = items.size();
        if (n != expectedItems.length) {
            throw fail("Number of items differ. Expected: " + expectedItems.length + ", Actual: " + n + '.');
        }
        for (int i = 0; i < n; i++) {
            final T actualItem = items.get(i);
            final T expectedItem = expectedItems[i];
            if (!Objects.equals(expectedItem, actualItem)) {
                throw fail("Item @ index " + i + " differ. Expected: " + valueAndClass(expectedItem) +
                           ", Actual: " + valueAndClass(actualItem) + '.');
            }
        }
        return this;
    }

    /**
     * Asserts that this {@link TestSubscriber} has received exactly one {@link #onComplete()} call.
     * @throws AssertionError if there was none, more than one {@link #onComplete} call or there are also errors
     */
    final TestSubscriber<T> assertComplete() {
        final int c = completions;
        if (c == 0) {
            throw fail("onComplete not called.");
        }
        if (c > 1) {
            throw fail("onComplete called too many times.");
        }
        if (!errors.isEmpty()) {
            throw fail("onComplete called but there are errors.");
        }
        return this;
    }

    /**
     * Asserts that this {@link TestSubscriber} has received exactly one {@link Throwable} (subclass) of the
     * given {@link Class}.
     * @param clazz the expected (parent) class of the Throwable received
     * @throws AssertionError if no errors were received, different error(s) were received, the same error
     *                        received multiple times or there were also {@link #onComplete} calls as well.
     */
    final TestSubscriber<T> assertError(Class<? extends Throwable> clazz) {
        if (errors.isEmpty()) {
            throw fail("onError not called");
        }

        int found = 0;
        for (Throwable ex : errors) {
            if (clazz.isInstance(ex)) {
                found++;
            }
        }

        if (found == 0) {
            throw fail("Error not found: " + clazz);
        }
        if (found > 1) {
            throw fail("Multiple onError calls with " + clazz);
        }
        if (completions != 0) {
            throw fail("Error found but there were onComplete calls as well");
        }

        return this;
    }

    /**
     * Asserts that the upstream called {@link #onSubscribe}.
     * @throws AssertionError if {@link #onSubscribe} was not yet called
     */
    final TestSubscriber<T> assertOnSubscribe() {
        if (upstream.get() == null) {
            throw fail("onSubscribe not called");
        }
        return this;
    }

    /**
     * Asserts that this {@link TestSubscriber} received the given expected items in the expected order and
     * then completed normally.
     * @param expectedItems the varargs of items expected
     */
    @SafeVarargs
    final TestSubscriber<T> assertResult(T... expectedItems) {
        requireNonNull(expectedItems, "expectedItems");
        assertOnSubscribe();
        assertValues(expectedItems);
        assertComplete();
        return this;
    }

    /**
     * Asserts that this {@link TestSubscriber} received the given expected items in the expected order
     * and the received an {@link #onError} that is an instance of the given class.
     * @param clazz the expected (parent) class of the Throwable received
     * @param expectedItems the vararg array of the expected items
     */
    @SafeVarargs
    final TestSubscriber<T> assertFailure(Class<? extends Throwable> clazz, T... expectedItems) {
        requireNonNull(clazz, "clazz");
        requireNonNull(expectedItems, "expectedItems");
        assertOnSubscribe();
        assertValues(expectedItems);
        assertError(clazz);
        return this;
    }

    /**
     * Asserts that this {@link TestSubscriber} received the given number of items.
     * @param count the expected item count
     * @throws AssertionError if the number of items received differs from the given {@code count}
     */
    final TestSubscriber<T> assertItemCount(int count) {
        final int n = items.size();
        if (n != count) {
            throw fail("Number of items differ. Expected: " + count + ", Actual: " + n + '.');
        }
        return this;
    }

    /**
     * Asserts that there were no items or terminal events received by this {@link TestSubscriber}.
     * @throws AssertionError if items or terminal events were received
     */
    final TestSubscriber<T> assertEmpty() {
        assertOnSubscribe();
        assertItemCount(0);
        assertNotTerminated();
        return this;
    }

    /**
     * Asserts that there were no items or terminal events received by this {@link TestSubscriber}.
     * @throws AssertionError if items or terminal events were received
     */
    @SafeVarargs
    final TestSubscriber<T> assertValuesOnly(T... expectedItems) {
        requireNonNull(expectedItems, "expectedItems");
        assertOnSubscribe();
        assertValues(expectedItems);
        assertNotTerminated();
        return this;
    }

    /**
     * Asserts that there were no terminal events received by this {@link TestSubscriber}.
     * @throws AssertionError if terminal events were received
     */
    final TestSubscriber<T> assertNotTerminated() {
        if (!errors.isEmpty()) {
            throw fail("Unexpected error(s) present.");
        }
        if (completions != 0) {
            throw fail("Unexpected completion(s).");
        }
        return this;
    }

    /**
     * Awaits the termination of this TestSubscriber in a blocking manner.
     * <p>
     *     If the timeout elapses first, a {@link TimeoutException}
     *     is added to the error list.
     *     If the current thread waiting is interrupted, the
     *     {@link InterruptedException} is added to the error list
     * </p>
     * @param timeout the time to wait
     * @param unit the time unit
     */
    final TestSubscriber<T> awaitDone(long timeout, TimeUnit unit) {
        requireNonNull(unit, "unit");
        try {
            if (!latch.await(timeout, unit)) {
                cancel();
                errors.add(new TimeoutException());
            }
        } catch (InterruptedException ex) {
            cancel();
            errors.add(ex);
        }
        return this;
    }

    /**
     * Awaits the upstream to produce at least the given number of items within
     * 5 seconds, sleeping for 10 milliseconds at a time.
     * <p>
     *     If the timeout elapses first, a {@link TimeoutException}
     *     is added to the error list.
     *     If the current thread waiting is interrupted, the
     *     {@link InterruptedException} is added to the error list
     * </p>
     * @param count the number of items to wait for
     * @see #awaitCount(int, long, long, TimeUnit)
     */
    final TestSubscriber<T> awaitCount(int count) {
        return awaitCount(count, 10, 5000, TimeUnit.MILLISECONDS);
    }

    /**
     * Awaits the upstream to produce at least the given number of items within the
     * specified time window.
     * <p>
     *     If the timeout elapses first, a {@link TimeoutException}
     *     is added to the error list.
     *     If the current thread waiting is interrupted, the
     *     {@link InterruptedException} is added to the error list
     * </p>
     * @param count the number of items to wait for
     * @param sleep the time to sleep between count checks
     * @param timeout the maximum time to wait for the items
     * @param unit the time unit
     */
    final TestSubscriber<T> awaitCount(int count, long sleep, long timeout, TimeUnit unit) {
        requireNonNull(unit, "unit");
        final long end = unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS) + timeout;

        for (;;) {
            if (items.size() >= count) {
                break;
            }
            final long now = unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (now > end) {
                cancel();
                errors.add(new TimeoutException());
                break;
            }
            try {
                unit.sleep(sleep);
            } catch (InterruptedException ex) {
                cancel();
                errors.add(ex);
                break;
            }
        }
        return this;
    }
}
