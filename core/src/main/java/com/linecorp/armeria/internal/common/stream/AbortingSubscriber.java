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
 * under the Licenses
 */

package com.linecorp.armeria.internal.common.stream;

import java.lang.reflect.Field;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;

public final class AbortingSubscriber<T> implements Subscriber<T> {

    private static final AbortedStreamException ABORTED_STREAM_EXCEPTION_INSTANCE;

    static {
        try {
            // Avoid making AbortedStreamException#INSTANCE as public.
            final Field field = AbortedStreamException.class.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            ABORTED_STREAM_EXCEPTION_INSTANCE = (AbortedStreamException) field.get(null);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public static final AbortingSubscriber<Object> INSTANCE =
            new AbortingSubscriber<>(ABORTED_STREAM_EXCEPTION_INSTANCE);

    @SuppressWarnings("unchecked")
    public static <T> AbortingSubscriber<T> get(@Nullable Throwable cause) {
        return cause == null || cause == ABORTED_STREAM_EXCEPTION_INSTANCE ? (AbortingSubscriber<T>) INSTANCE
                                                                           : new AbortingSubscriber<>(cause);
    }

    private final Throwable cause;

    private AbortingSubscriber(Throwable cause) {
        this.cause = cause;
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.cancel();
    }

    @Override
    public void onNext(T o) {}

    @Override
    public void onError(Throwable cause) {}

    @Override
    public void onComplete() {}

    /**
     * Returns the cause which tells why the stream has been aborted.
     */
    public Throwable cause() {
        return cause;
    }
}
