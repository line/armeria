/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.FastThreadLocal;

final class ClientThreadLocalState {

    private static final FastThreadLocal<ClientThreadLocalState> threadLocalState = new FastThreadLocal<>();

    @Nullable
    static ClientThreadLocalState get() {
        return threadLocalState.get();
    }

    static ClientThreadLocalState maybeCreate() {
        ClientThreadLocalState state = threadLocalState.get();
        if (state == null) {
            state = new ClientThreadLocalState();
            threadLocalState.set(state);
        }
        return state;
    }

    @Nullable
    private ArrayList<Consumer<? super ClientRequestContext>> customizers;

    @Nullable
    private DefaultClientRequestContextCaptor pendingContextCaptor;

    void add(Consumer<? super ClientRequestContext> customizer) {
        if (customizers == null) {
            customizers = new ArrayList<>();
        }
        customizers.add(customizer);
    }

    void remove(Consumer<? super ClientRequestContext> customizer) {
        if (customizers != null) {
            // Iterate in reverse order since we add/remove in LIFO order.
            for (int i = customizers.size() - 1; i >= 0; i--) {
                if (customizers.get(i) == customizer) {
                    customizers.remove(i);
                    maybeRemoveThreadLocal();
                    return;
                }
            }
        }

        // Should not reach here, but may happen if a user tried to call from a wrong thread.
        reportThreadSafetyViolation();
    }

    ClientRequestContextCaptor newContextCaptor() {
        final DefaultClientRequestContextCaptor oldPendingContextCaptor = pendingContextCaptor;
        return pendingContextCaptor = new DefaultClientRequestContextCaptor(oldPendingContextCaptor);
    }

    void addCapturedContext(ClientRequestContext ctx) {
        if (pendingContextCaptor != null) {
            pendingContextCaptor.add(ctx);
        }
    }

    void maybeRemoveThreadLocal() {
        if (pendingContextCaptor != null || customizers != null && !customizers.isEmpty()) {
            // State not empty. Do not remove.
            return;
        }

        final ClientThreadLocalState actualState = threadLocalState.get();
        if (actualState != this) {
            reportThreadSafetyViolation();
            return;
        }

        threadLocalState.remove();
    }

    private static void reportThreadSafetyViolation() {
        final String safeCloseable = SafeCloseable.class.getSimpleName();
        throw new IllegalStateException(
                "Failed to remove a context customizer. Did you call " + safeCloseable +
                ".close() manually on a different thread? Use try-resources or make sure " + safeCloseable +
                ".close() is called on the same thread as the one that created it.");
    }

    @Nullable
    List<Consumer<? super ClientRequestContext>> copyCustomizers() {
        if (customizers == null || customizers.isEmpty()) {
            return null;
        }

        return ImmutableList.copyOf(customizers);
    }

    private final class DefaultClientRequestContextCaptor implements ClientRequestContextCaptor {

        final List<ClientRequestContext> captured = new ArrayList<>();

        @Nullable
        private DefaultClientRequestContextCaptor oldCaptor;

        DefaultClientRequestContextCaptor(@Nullable DefaultClientRequestContextCaptor oldCaptor) {
            this.oldCaptor = oldCaptor;
        }

        void add(ClientRequestContext ctx) {
            captured.add(ctx);
            if (oldCaptor != null) {
                oldCaptor.add(ctx);
            }
        }

        @Override
        public ClientRequestContext get() {
            if (captured.isEmpty()) {
                throw new NoSuchElementException("No context was captured; no request was made?");
            }
            return captured.get(0);
        }

        @Nullable
        @Override
        public ClientRequestContext getOrNull() {
            if (!captured.isEmpty()) {
                return captured.get(0);
            }
            return null;
        }

        @Override
        public List<ClientRequestContext> getAll() {
            return ImmutableList.copyOf(captured);
        }

        @Override
        public int size() {
            return captured.size();
        }

        @Override
        public void close() {
            pendingContextCaptor = oldCaptor;
            oldCaptor = null;
            maybeRemoveThreadLocal();
        }
    }
}
