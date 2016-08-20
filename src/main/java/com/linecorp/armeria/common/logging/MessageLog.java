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

import java.util.Iterator;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

import io.netty.util.Attribute;
import io.netty.util.AttributeMap;

/**
 * Information collected while processing a {@link Request} or a {@link Response}.
 *
 * @see MessageLogBuilder
 */
public interface MessageLog extends AttributeMap {

    /**
     * Returns the length of the message content.
     */
    long contentLength();

    /**
     * Returns the {@link System#nanoTime() nanoTime} of when the processing of the message started.
     */
    long startTimeNanos();

    /**
     * Returns the {@link System#nanoTime() nanoTime} of when the processing of the message ended.
     */
    long endTimeNanos();

    /**
     * Returns the cause of message processing failure.
     *
     * @return the cause. {@code null} if the message was processed completely.
     */
    Throwable cause(); // non-null if failed without sending a full message.

    /**
     * Returns all {@link Attribute}s of this log.
     */
    Iterator<Attribute<?>> attrs();
}
