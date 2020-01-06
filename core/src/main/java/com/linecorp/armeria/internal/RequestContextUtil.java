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

package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Utilities for {@link RequestContext}.
 */
public final class RequestContextUtil {

    private static final SafeCloseable noopSafeCloseable = () -> { /* no-op */ };

    /**
     * Returns the {@link SafeCloseable} which doesn't do anything.
     */
    public static SafeCloseable noopSafeCloseable() {
        return noopSafeCloseable;
    }

    /**
     * Runs callbacks if {@code runCallbacks} is {@code true} and returns the {@link SafeCloseable}
     * which will remove the current {@link RequestContext} in the thread-local when
     * {@link SafeCloseable#close()} is invoked.
     */
    public static SafeCloseable pushWithoutRootCtx(RequestContext currentCtx, boolean runCallbacks) {
        requireNonNull(currentCtx, "currentCtx");
        if (runCallbacks) {
            currentCtx.invokeOnEnterCallbacks();
            return () -> {
                currentCtx.invokeOnExitCallbacks();
                RequestContextThreadLocal.remove();
            };
        } else {
            return RequestContextThreadLocal::remove;
        }
    }

    /**
     * Runs callbacks if {@code runCallbacks} is {@code true} and returns the {@link SafeCloseable}
     * which will set the root in the thread-local when {@link SafeCloseable#close()} is invoked.
     */
    public static SafeCloseable pushWithRootCtx(ClientRequestContext currentCtx, ServiceRequestContext root,
                                                boolean runCallbacks) {
        return pushWithRootAndOldCtx(currentCtx, root, root, runCallbacks);
    }

    /**
     * Runs callbacks in {@code currentCtx} and {@code root} if {@code runCallbacks} is {@code true} and
     * returns the {@link SafeCloseable} which will set the {@code oldCtx} in the thread-local
     * when {@link SafeCloseable#close()} is invoked.
     */
    public static SafeCloseable pushWithRootAndOldCtx(ClientRequestContext currentCtx,
                                                      ServiceRequestContext root,
                                                      RequestContext oldCtx, boolean runCallbacks) {
        requireNonNull(currentCtx, "currentCtx");
        requireNonNull(root, "root");
        requireNonNull(oldCtx, "oldCtx");
        if (runCallbacks) {
            root.invokeOnChildCallbacks(currentCtx);
            currentCtx.invokeOnEnterCallbacks();
            return () -> {
                currentCtx.invokeOnExitCallbacks();
                RequestContextThreadLocal.set(oldCtx);
            };
        } else {
            return () -> RequestContextThreadLocal.set(oldCtx);
        }
    }

    /**
     * An {@link IllegalStateException} which is raised when pushing a context from the unexpected thread
     * or forgetting to close the previous context.
     */
    public static final class IllegalContextPushingException extends IllegalStateException {

        private static final long serialVersionUID = 5431942355463120798L;

        public IllegalContextPushingException(RequestContext newCtx, RequestContext oldCtx) {
            super("Trying to call object wrapped with context " + newCtx + ", but context is currently " +
                  "set to " + oldCtx + ". This means the callback was called from " +
                  "unexpected thread or forgetting to close previous context.");
        }
    }

    private RequestContextUtil() {}
}
