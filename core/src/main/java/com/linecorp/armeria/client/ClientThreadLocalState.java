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

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;

final class ClientThreadLocalState {

    @Nullable
    private ArrayList<Consumer<? super ClientRequestContext>> customizers;

    @Nullable
    private ClientRequestContextCaptor pendingContextCaptor;

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
                    return;
                }
            }
        }

        // Should not reach here, but may happen if a user tried to call from a wrong thread.
        final String safeCloseable = SafeCloseable.class.getSimpleName();
        throw new IllegalStateException(
                "Failed to remove a context customizer. Did you call " + safeCloseable +
                ".close() manually on a different thread? Use try-resources or make sure " + safeCloseable +
                ".close() is called on the same thread as the one that created it.");
    }

    Supplier<ClientRequestContext> newContextCaptor() {
        return pendingContextCaptor = new ClientRequestContextCaptor();
    }

    void setCapturedContext(ClientRequestContext ctx) {
        if (pendingContextCaptor != null) {
            pendingContextCaptor.ctx = ctx;
            pendingContextCaptor = null;
        }
    }

    @Nullable
    List<Consumer<? super ClientRequestContext>> copyCustomizers() {
        if (customizers == null || customizers.isEmpty()) {
            return null;
        }

        return ImmutableList.copyOf(customizers);
    }

    private static final class ClientRequestContextCaptor implements Supplier<ClientRequestContext> {

        @Nullable
        private ClientRequestContext ctx;

        @Override
        public ClientRequestContext get() {
            checkState(ctx != null, "No context was captured; no request was made?");
            return ctx;
        }
    }
}
