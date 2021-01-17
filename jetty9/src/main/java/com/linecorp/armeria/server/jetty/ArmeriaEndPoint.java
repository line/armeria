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
 * under the License.
 */
package com.linecorp.armeria.server.jetty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.Callback;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.server.ServiceRequestContext;

final class ArmeriaEndPoint implements EndPoint {

    private static final AtomicReferenceFieldUpdater<ArmeriaEndPoint, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(ArmeriaEndPoint.class, State.class, "state");

    private final ServiceRequestContext ctx;
    private final InetSocketAddress localAddress;

    @Nullable
    private volatile Connection connection;
    private volatile State state = State.OPEN;

    // Helpers
    private final FillInterest fillInterest = new FillInterest() {
        @Override
        protected void needsFillInterest() {}
    };

    private final WriteFlusher writeFlusher = new WriteFlusher(this) {
        @Override
        protected void onIncompleteFlush() {}
    };

    ArmeriaEndPoint(ServiceRequestContext ctx, @Nullable String hostname) {
        this.ctx = ctx;
        localAddress = addHostname(ctx.localAddress(), hostname);

        setIdleTimeout(getIdleTimeout());
    }

    /**
     * Adds the hostname string to the specified {@link InetSocketAddress} so that
     * Jetty's {@code ServletRequest.getLocalName()} implementation returns the configured hostname.
     */
    private static InetSocketAddress addHostname(InetSocketAddress address, @Nullable String hostname) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(
                    hostname, address.getAddress().getAddress()), address.getPort());
        } catch (UnknownHostException e) {
            throw new Error(e); // Should never happen
        }
    }

    @Override
    public long getCreatedTimeStamp() {
        return ctx.log().partial().requestStartTimeMillis();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return ctx.remoteAddress();
    }

    @Override
    @Nullable
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    public boolean isOptimizedForDirectBuffers() {
        return false;
    }

    @Override
    @Nullable
    public Object getTransport() {
        return null;
    }

    @Override
    public long getIdleTimeout() {
        return 0;
    }

    @Override
    public void setIdleTimeout(long idleTimeout) {}

    @Override
    public boolean isOpen() {
        return state != State.CLOSED;
    }

    @Override
    public boolean isInputShutdown() {
        return state != State.CLOSED;
    }

    @Override
    public boolean isOutputShutdown() {
        return state != State.OPEN;
    }

    @Override
    public void shutdownOutput() {
        stateUpdater.compareAndSet(this, State.OPEN, State.OUTPUT_SHUTDOWN);
    }

    @Override
    public void close() {
        close(null);
    }

    private void close(@Nullable Throwable failure) {
        for (;;) {
            final State oldState = state;
            if (oldState == State.CLOSED) {
                break;
            }

            if (stateUpdater.compareAndSet(this, oldState, State.CLOSED)) {
                if (failure == null) {
                    onClose();
                } else {
                    onClose(failure);
                }
            }
        }
    }

    @Override
    public void onOpen() {}

    @Override
    public void onClose() {
        writeFlusher.onClose();
        fillInterest.onClose();
    }

    private void onClose(Throwable failure) {
        writeFlusher.onFail(failure);
        fillInterest.onFail(failure);
    }

    @Override
    public int fill(ByteBuffer buffer) {
        return 0;
    }

    @Override
    public boolean flush(ByteBuffer... buffer) {
        return true;
    }

    @Override
    public void fillInterested(Callback callback) {
        fillInterest.register(callback);
    }

    @Override
    public boolean tryFillInterested(Callback callback) {
        return fillInterest.tryRegister(callback);
    }

    @Override
    public boolean isFillInterested() {
        return fillInterest.isInterested();
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) {
        writeFlusher.write(callback, buffers);
    }

    @Override
    public void upgrade(Connection newConnection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(ctx).toString();
    }

    private enum State {
        OPEN, OUTPUT_SHUTDOWN, CLOSED
    }
}
