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

package com.linecorp.armeria.client.logging;

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.common.util.Ticker;

/**
 * Decorates a {@link Client} to log invocation requests and responses.
 */
public class LoggingClient extends DecoratingClient {

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    public LoggingClient(Client client) {
        super(client, codec -> new LoggingClientCodec(codec, Ticker.systemTicker()), Function.identity());
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     *
     * @param ticker an alternative {@link Ticker}
     */
    public LoggingClient(Client client, Ticker ticker) {
        super(client, codec -> new LoggingClientCodec(codec, ticker), Function.identity());
    }
}
