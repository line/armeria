/*
 * Copyright 2024 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Specifies when to start scheduling a response timeout. Note that this value does not affect calculation
 * of the response timeout, but determines when the response timeout will affect the ongoing request.
 *
 * <p>For example, assume the following scenario with a responseTimeout of 2 seconds:
 * <pre>{@code
 * |---request start---decorators(3s)---connection acquisition(2s)---request write/response read---|
 * }</pre>
 * <ul>
 *     <li>
 *         If {@link ResponseTimeoutMode#REQUEST_START} is used, the timeout task will be scheduled
 *         immediately on request start. The timeout task will trigger while the request goes through
 *         the decorator, and the request will fail before acquiring a new connection.
 *     </li>
 *     <li>
 *         If {@link ResponseTimeoutMode#REQUEST_SENT} is used, the request will go through the decorators,
 *         acquire a connection, and write the request. Once the request is fully sent, the timeout task
 *         will trigger immediately since 5 seconds has passed which exceeds the responseTimeout of 2 seconds.
 *     </li>
 * </ul>
 */
@UnstableApi
public enum ResponseTimeoutMode {

    /**
     * The response timeout is scheduled when the request first starts to execute. More specifically,
     * the scheduling will take place when the request starts to go through the decorator chain.
     */
    REQUEST_START,

    /**
     * The response timeout is scheduled after the connection is acquired.
     */
    CONNECTION_ACQUIRED,

    /**
     * The response timeout is scheduled either after the client fully writes the request
     * or when the response bytes are first read.
     */
    REQUEST_SENT,
}
