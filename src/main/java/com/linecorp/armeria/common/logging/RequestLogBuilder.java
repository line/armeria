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

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;

/**
 * Updates a {@link RequestLog} with newly available information.
 */
public interface RequestLogBuilder extends MessageLogBuilder {
    /**
     * Starts the collection of information. This method will update the following properties:
     * <ul>
     *   <li>{@link MessageLog#startTimeNanos()}</li>
     *   <li>{@link RequestLog#scheme()} with {@link SerializationFormat#UNKNOWN}</li>
     *   <li>{@link RequestLog#host()}</li>
     *   <li>{@link RequestLog#method()}</li>
     *   <li>{@link RequestLog#path()}</li>
     * </ul>
     * This method will do nothing if called twice.
     */
    void start(Channel channel, SessionProtocol sessionProtocol, String host, String method, String path);

    /**
     * Updates the {@link SerializationFormat}.
     */
    void serializationFormat(SerializationFormat serializationFormat);
}
