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
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

final class ClientRequestContextCustomizers {

    @Nullable
    private ArrayList<Consumer<? super ClientRequestContext>> customizers;

    private boolean pendingContextCapture;
    @Nullable
    private ClientRequestContext capturedContext;

    void add(Consumer<? super ClientRequestContext> customizer) {
        if (customizers == null) {
            customizers = new ArrayList<>();
        }
        customizers.add(customizer);
    }

    void remove(Consumer<? super ClientRequestContext> customizer) {
        if (customizers != null) {
            for (int i = customizers.size() - 1; i >= 0; i--) {
                if (customizers.get(i) == customizer) {
                    customizers.remove(i);
                    return;
                }
            }
        }

        // Should not reach here, but may happen if a user tried to call from a wrong thread.
        throw new IllegalStateException("Failed to remove a context customizer: " + customizer);
    }

    void captureNextContext() {
        pendingContextCapture = true;
    }

    @Nullable
    ClientRequestContext capturedContext() {
        final ClientRequestContext capturedContext = this.capturedContext;
        this.capturedContext = null;
        pendingContextCapture = false;
        return capturedContext;
    }

    void setCapturedContext(ClientRequestContext ctx) {
        if (pendingContextCapture) {
            capturedContext = ctx;
            pendingContextCapture = false;
        }
    }

    @Nullable
    List<Consumer<? super ClientRequestContext>> copyCustomizers() {
        if (customizers == null || customizers.isEmpty()) {
            return null;
        }

        return ImmutableList.copyOf(customizers);
    }
}
