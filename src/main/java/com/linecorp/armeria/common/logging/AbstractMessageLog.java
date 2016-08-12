/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.UnitFormatter;
import com.linecorp.armeria.internal.DefaultAttributeMap;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

abstract class AbstractMessageLog<T extends MessageLog>
        extends CompletableFuture<T> implements MessageLog, MessageLogBuilder {

    private final DefaultAttributeMap attrs = new DefaultAttributeMap();
    private boolean startTimeNanosSet;
    private long startTimeNanos;
    private long contentLength;
    private long endTimeNanos;
    private Throwable cause;

    boolean start0() {
        if (isDone() || startTimeNanosSet) {
            return false;
        }

        startTimeNanos = System.nanoTime();
        startTimeNanosSet = true;
        return true;
    }

    @Override
    public long startTimeNanos() {
        return startTimeNanos;
    }

    @Override
    public void increaseContentLength(long deltaBytes) {
        if (deltaBytes < 0) {
            throw new IllegalArgumentException("deltaBytes: " + deltaBytes + " (expected: >= 0)");
        }
        if (isDone()) {
            return;
        }

        contentLength += deltaBytes;
    }

    @Override
    public void contentLength(long contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength: " + contentLength + " (expected: >= 0)");
        }
        if (isDone()) {
            return;
        }

        this.contentLength = contentLength;
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public <V> Attribute<V> attr(AttributeKey<V> key) {
        return attrs.attr(key);
    }

    @Override
    public <V> boolean hasAttr(AttributeKey<V> key) {
        return attrs.hasAttr(key);
    }

    @Override
    public Iterator<Attribute<?>> attrs() {
        return attrs.attrs();
    }

    @Override
    public void end() {
        end0(null);
    }

    @Override
    public void end(Throwable cause) {
        requireNonNull(cause, "cause");
        end0(cause);
    }

    private void end0(Throwable cause) {
        if (isDone()) {
            return;
        }

        this.cause = cause;

        // Handle the case where end() was called without start()
        start0();

        final Iterator<Attribute<?>> attrs = attrs();
        if (attrs.hasNext()) {
            final List<CompletableFuture<?>> dependencies = new ArrayList<>(4);
            do {
                final Object a = attrs.next().get();
                if (a instanceof CompletableFuture) {
                    final CompletableFuture<?> f = (CompletableFuture<?>) a;
                    if (!f.isDone()) {
                        dependencies.add(f);
                    }
                }
            } while (attrs.hasNext());

            final CompletableFuture<?> future;
            switch (dependencies.size()) {
                case 0:
                    complete();
                    return;
                case 1:
                    future = dependencies.get(0);
                    break;
                default:
                    future = CompletableFuture.allOf(
                            dependencies.toArray(new CompletableFuture<?>[dependencies.size()]));
            }

            future.handle((unused1, unused2) -> complete())
                  .exceptionally(CompletionActions::log);
        } else {
            complete();
        }
    }

    private Void complete() {
        endTimeNanos = System.nanoTime();
        complete(self());
        return null;
    }

    @Override
    public long endTimeNanos() {
        return endTimeNanos;
    }

    @Override
    public Throwable cause() {
        return cause;
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }

    @Override
    public final String toString() {
        final MoreObjects.ToStringHelper helper =
                MoreObjects.toStringHelper("")
                           .add("timeSpan",
                                startTimeNanos + "+" + UnitFormatter.elapsed(startTimeNanos, endTimeNanos))
                           .add("contentLength", UnitFormatter.size(contentLength));

        append(helper);

        return helper.add("cause", cause)
                     .add("attrs", attrs).toString();
    }

    abstract void append(MoreObjects.ToStringHelper helper);
}
