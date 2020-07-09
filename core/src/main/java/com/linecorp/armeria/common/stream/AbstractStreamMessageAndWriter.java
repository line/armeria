/*
 * Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.unsafe.PooledObjects;

abstract class AbstractStreamMessageAndWriter<T> extends AbstractStreamMessage<T>
        implements StreamMessageAndWriter<T> {

    enum State {
        /**
         * The initial state. Will enter {@link #CLOSED} or {@link #CLEANUP}.
         */
        OPEN,
        /**
         * {@link #close()} or {@link #close(Throwable)} has been called. Will enter {@link #CLEANUP} after
         * {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)} is invoked.
         */
        CLOSED,
        /**
         * Anything in the queue must be cleaned up.
         * Enters this state when there's no chance of consumption by subscriber.
         * i.e. when any of the following methods are invoked:
         * <ul>
         *   <li>{@link Subscription#cancel()}</li>
         *   <li>{@link #abort()} (via {@link AbortingSubscriber})</li>
         *   <li>{@link Subscriber#onComplete()}</li>
         *   <li>{@link Subscriber#onError(Throwable)}</li>
         * </ul>
         */
        CLEANUP
    }

    @Override
    public boolean tryWrite(T obj) {
        requireNonNull(obj, "obj");

        if (!(obj instanceof HttpData) || !((HttpData) obj).isPooled()) {
            // obj is not a pooled HttpData.
            if (!isOpen()) {
                return false;
            }
        } else {
            // obj is a pooled HttpData.
            final HttpData data = (HttpData) obj;
            if (!isOpen()) {
                data.close();
                return false;
            }

            PooledObjects.touch(data);
        }

        addObject(obj);
        return true;
    }

    @Override
    public CompletableFuture<Void> whenConsumed() {
        final AwaitDemandFuture f = new AwaitDemandFuture();
        if (!isOpen()) {
            f.completeExceptionally(ClosedStreamException.get());
            return f;
        }

        addObjectOrEvent(f);
        return f;
    }

    /**
     * Adds an object to publish to the stream.
     */
    abstract void addObject(T obj);

    /**
     * Adds an object to publish (of type {@code T} or an event (e.g., {@link CloseEvent},
     * {@link AwaitDemandFuture}) to the stream.
     */
    abstract void addObjectOrEvent(Object obj);

    static final class AwaitDemandFuture extends CompletableFuture<Void> {}
}
