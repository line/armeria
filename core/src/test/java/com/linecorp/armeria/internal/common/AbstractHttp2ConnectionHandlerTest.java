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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler.isGoAwaySentException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Connection.Endpoint;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2RemoteFlowController;

class AbstractHttp2ConnectionHandlerTest {

    final Http2Exception goAwaySentException =
            new Http2Exception(Http2Error.PROTOCOL_ERROR, "Stream 123 does not exist");
    @Test
    void expectedGoAwaySentException() {
        final Http2Connection connection = mock(Http2Connection.class);
        when(connection.goAwaySent()).thenReturn(true);
        final Endpoint<Http2RemoteFlowController> endpoint = mock(Endpoint.class);
        when(endpoint.isValidStreamId(123)).thenReturn(true);
        when(endpoint.lastStreamKnownByPeer()).thenReturn(122);
        when(connection.remote()).thenReturn(endpoint);

        assertThat(isGoAwaySentException(goAwaySentException, connection)).isTrue();
    }

    @Test
    void unexpectedGoAwaySentException() {
        final Http2Connection connection = mock(Http2Connection.class);
        when(connection.goAwaySent()).thenReturn(false);
        // Should return false if a GOAWAY frame was not sent.
        assertThat(isGoAwaySentException(goAwaySentException, connection)).isFalse();

        when(connection.goAwaySent()).thenReturn(true);
        final Endpoint<Http2RemoteFlowController> endpoint = mock(Endpoint.class);
        when(endpoint.isValidStreamId(123)).thenReturn(false);
        when(connection.remote()).thenReturn(endpoint);
        // Should return false if a stream ID is not a valid one
        assertThat(isGoAwaySentException(goAwaySentException, connection)).isFalse();

        when(endpoint.isValidStreamId(123)).thenReturn(true);
        when(endpoint.lastStreamKnownByPeer()).thenReturn(124);
        // Should return false if a stream ID is less than the last known stream ID.
        assertThat(isGoAwaySentException(goAwaySentException, connection)).isFalse();
    }
}
