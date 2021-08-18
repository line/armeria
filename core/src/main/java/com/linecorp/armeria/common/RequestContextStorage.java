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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Unwrappable;
import com.linecorp.armeria.internal.common.RequestContextUtil;

/**
 * The storage for storing {@link RequestContext}.
 *
 * <p>If you want to implement your own storage or add some hooks when a {@link RequestContext} is pushed
 * and popped, you should use {@link RequestContextStorageProvider}.
 * Here's an example that sets MDC before {@link RequestContext} is pushed:
 *
 * <pre>{@code
 * > public class MyStorage implements RequestContextStorageProvider {
 * >
 * >     @Override
 * >     public RequestContextStorage newStorage() {
 * >         RequestContextStorage threadLocalStorage = RequestContextStorage.threadLocal();
 * >         return new RequestContextStorage() {
 * >
 * >             @Nullable
 * >             @Override
 * >             public <T extends RequestContext> T push(RequestContext toPush) {
 * >                 setMdc(toPush);
 * >                 return threadLocalStorage.push(toPush);
 * >             }
 * >
 * >             @Override
 * >             public void pop(RequestContext current, @Nullable RequestContext toRestore) {
 * >                 clearMdc();
 * >                 if (toRestore != null) {
 * >                     setMdc(toRestore);
 * >                 }
 * >                 threadLocalStorage.pop(current, toRestore);
 * >             }
 * >             ...
 * >          }
 * >     }
 * > }
 * }</pre>
 */
@UnstableApi
public interface RequestContextStorage extends Unwrappable {

    /**
     * Customizes the current {@link RequestContextStorage} by applying the specified {@link Function} to it.
     * This method is useful when you need to perform an additional operation when a {@link RequestContext}
     * is pushed or popped. Note:
     * <ul>
     *   <li>All {@link RequestContextStorage} operations are highly performance-sensitive operation and thus
     *       it's not a good idea to run a time-consuming task.</li>
     *   <li>This method must be invoked at the beginning of application startup, e.g.
     *       Never call this method in the middle of request processing.</li>
     * </ul>
     */
    static void hook(Function<? super RequestContextStorage, ? extends RequestContextStorage> function) {
        RequestContextUtil.hook(requireNonNull(function, "function"));
    }

    /**
     * Returns the default {@link RequestContextStorage} which stores the {@link RequestContext}
     * in the thread-local.
     */
    static RequestContextStorage threadLocal() {
        return ThreadLocalRequestContextStorage.INSTANCE;
    }

    /**
     * Pushes the specified {@link RequestContext} into the storage.
     *
     * @return the old {@link RequestContext} which was in the storage before the specified {@code toPush} is
     *         pushed. {@code null}, if there was no {@link RequestContext}.
     */
    @Nullable
    <T extends RequestContext> T push(RequestContext toPush);

    /**
     * Pops the current {@link RequestContext} in the storage and pushes back the specified {@code toRestore}.
     * {@code toRestore} is the {@link RequestContext} returned from when
     * {@linkplain #push(RequestContext) push(current)} is called, so it can be {@code null}.
     *
     * <p>The specified {@code current} must be the {@link RequestContext} in the storage. If it's not,
     * it means that {@link RequestContext#push()} is not called using {@code try-with-resources} block, so
     * the previous {@link RequestContext} is not popped properly.
     */
    void pop(RequestContext current, @Nullable RequestContext toRestore);

    /**
     * Returns the {@link RequestContext} in the storage. {@code null} if there is no {@link RequestContext}.
     */
    @Nullable
    <T extends RequestContext> T currentOrNull();

    @Override
    default RequestContextStorage unwrap() {
        return this;
    }
}
