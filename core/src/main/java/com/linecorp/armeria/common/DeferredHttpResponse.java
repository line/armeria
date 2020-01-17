/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.stream.DeferredStreamMessage;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpResponse} whose stream is published later by another {@link HttpResponse}. It is used when
 * an {@link HttpResponse} will not be instantiated early.
 *
 * @deprecated Use {@link HttpResponse#from(CompletionStage)}.
 */
@Deprecated
public class DeferredHttpResponse extends DeferredStreamMessage<HttpObject> implements HttpResponse {

    @Nullable
    private final EventExecutor executor;

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link HttpResponse#from(CompletionStage)}.
     */
    @Deprecated
    public DeferredHttpResponse() {
        executor = null;
    }

    DeferredHttpResponse(EventExecutor executor) {
        this.executor = executor;
    }

    /**
     * Sets the delegate {@link HttpResponse} which will publish the stream actually.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    public void delegate(HttpResponse delegate) {
        super.delegate(delegate);
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        if (executor != null) {
            return executor;
        }
        return super.defaultSubscriberExecutor();
    }
}
