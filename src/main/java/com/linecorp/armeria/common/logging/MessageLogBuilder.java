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

import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AttributeMap;

/**
 * Updates a {@link MessageLog} with newly available information.
 */
public interface MessageLogBuilder extends AttributeMap {

    /**
     * Increases the {@link MessageLog#contentLength()} by {@code deltaBytes}.
     */
    void increaseContentLength(long deltaBytes);

    /**
     * Sets the {@link MessageLog#contentLength()} to the specified {@code contentLength}.
     */
    void contentLength(long contentLength);

    /**
     * Sets {@link MessageLog#endTimeNanos()} and finishes the collection of the information, completing
     * {@link RequestContext#requestLogFuture()} or {@link RequestContext#responseLogFuture()} successfully.
     * This method will do nothing if called twice.
     */
    void end();

    /**
     * Sets {@link MessageLog#endTimeNanos()} and finishes the collection of the information, completing
     * {@link RequestContext#requestLogFuture()} or {@link RequestContext#responseLogFuture()} exceptionally.
     * This method will do nothing if called twice.
     */
    void end(Throwable cause);
}
