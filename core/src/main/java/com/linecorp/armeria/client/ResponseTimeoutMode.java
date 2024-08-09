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

import java.time.Duration;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TimeoutMode;

/**
 * Specifies when to start scheduling a response timeout.
 *
 * <p>For example:
 * <pre>{@code
 * |---request start---decorators(3s)---connection acquisition(2s)---request write(5s)---response read(4s)---|
 * }</pre>
 * <ul>
 *     <li>
 *         If {@link ResponseTimeoutMode#FROM_START} is used, the timeout task will be scheduled
 *         immediately on request start. If the responseTimeout is less than 3 seconds, the timeout task
 *         will trigger while the request goes through the decorator, and the request will fail before
 *         acquiring a new connection. If the responseTimeout is greater than (3s + 2s + 5s + 4s) 14 seconds
 *         the request will complete successfully.
 *     </li>
 *     <li>
 *         If {@link ResponseTimeoutMode#CONNECTION_ACQUIRED} is used, the timeout task will be scheduled
 *         after connection acquisition. If the responseTimeout is greater than (5s + 4s) 9 seconds the
 *         request will complete successfully.
 *     </li>
 *     <li>
 *         If {@link ResponseTimeoutMode#REQUEST_SENT} is used, the the timeout task will be scheduled after
 *         the request is fully written. If the responseTimeout is greater than 4 seconds the request will
 *         complete successfully.
 *     </li>
 * </ul>
 *
 * <p>When {@link TimeoutMode#SET_FROM_NOW} is used, the timeout is assumed to be scheduled when
 * {@link ClientRequestContext#setResponseTimeout(TimeoutMode, Duration)} was called. If the request
 * has already reached {@link ResponseTimeoutMode}, then the timeout is scheduled normally.
 * If the request didn't reach {@link ResponseTimeoutMode} yet, the elapsed time is computed once
 * {@link ResponseTimeoutMode} is reached and the timeout is scheduled accordingly.
 * <pre>{@code
 * |---request start---decorators(3s)---connection acquisition(2s)---request write(5s)---response read(4s)---|
 * }</pre>
 *  Assume {@link ResponseTimeoutMode#FROM_START} is set, and {@link TimeoutMode#SET_FROM_NOW}
 *  with 1 second is called in the decorators. Then the timeout task will be triggered 1 second into connection
 *  acquisition.
 * <pre>{@code
 * |---request start---decorators(3s)---connection acquisition(2s)---request write(5s)---response read(4s)---|
 * }</pre>
 *  Assume {@link ResponseTimeoutMode#REQUEST_SENT} is set, and {@link TimeoutMode#SET_FROM_NOW}
 *  with 1 second is called in the decorators. The request will continue until the request is fully sent.
 *  Since (2s + 5s) 7 seconds have elapsed which is greater than the 1-second timeout, the timeout task will be
 *  invoked immediately before the response read starts.
 */
@UnstableApi
public enum ResponseTimeoutMode {

    /**
     * The response timeout is scheduled when the request first starts to execute. More specifically,
     * the scheduling will take place as soon as an endpoint is acquired but before the decorator chain
     * is traversed.
     */
    FROM_START,

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
