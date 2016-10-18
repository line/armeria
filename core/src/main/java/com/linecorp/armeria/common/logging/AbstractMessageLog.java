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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.UnitFormatter;
import com.linecorp.armeria.internal.DefaultAttributeMap;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

abstract class AbstractMessageLog<T extends MessageLog>
        extends CompletableFuture<T> implements MessageLog, MessageLogBuilder {

    private final DefaultAttributeMap attrs = new DefaultAttributeMap();
    private volatile boolean endCalled;
    private boolean startTimeNanosSet;
    private long startTimeNanos;
    private long contentLength;
    private long endTimeNanos;
    private Throwable cause;

    boolean start0() {
        if (endCalled) {
            return false;
        }

        return setStartTimeNanos();
    }

    private boolean setStartTimeNanos() {
        if (startTimeNanosSet) {
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
        if (endCalled) {
            return;
        }

        contentLength += deltaBytes;
    }

    @Override
    public void contentLength(long contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength: " + contentLength + " (expected: >= 0)");
        }
        if (endCalled) {
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
        if (endCalled) {
            return;
        }

        endCalled = true;
        this.cause = cause;

        // Handle the case where end() was called without start()
        setStartTimeNanos();

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

            future.whenComplete((unused1, unused2) -> complete());
        } else {
            complete();
        }
    }

    private void complete() {
        endTimeNanos = System.nanoTime();
        CompletableFuture<?> f = parentLogFuture();
        if (f != null && !f.isDone()) {
            f.whenComplete((unused1, unused2) -> complete(self()));
        } else {
            complete(self());
        }
    }

    abstract CompletableFuture<?> parentLogFuture();

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
        final StringBuilder buf = new StringBuilder(512);

        buf.append("{timeSpan=").append(startTimeNanos).append('+');
        UnitFormatter.appendElapsed(buf, startTimeNanos, endTimeNanos);

        buf.append(", contentLength=");
        UnitFormatter.appendSize(buf, contentLength);

        appendProperties(buf);

        buf.append(", cause=").append(cause);

        appendAttrs(buf);

        return buf.append('}').toString();
    }

    abstract void appendProperties(StringBuilder buf);

    private void appendAttrs(StringBuilder buf) {
        for (Iterator<Attribute<?>> i = attrs.attrs(); i.hasNext();) {
            final Attribute<?> a = i.next();
            if (!includeAttr(a)) {
                continue;
            }

            final String name = a.key().name();
            final Object value = a.get();

            buf.append(", ");

            final int sharpIndex = name.lastIndexOf('#');
            if (sharpIndex > 0) {
                buf.append(name, sharpIndex + 1, name.length());
            } else {
                buf.append(name);
            }

            buf.append('=');

            if (value instanceof Object[]) {
                buf.append(Arrays.toString((Object[]) value));
            } else {
                buf.append(value);
            }
        }
    }

    abstract boolean includeAttr(Attribute<?> attr);
}
