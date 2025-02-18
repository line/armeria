/*
 * Copyright 2025 LINE Corporation
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

import static com.linecorp.armeria.client.TimeoutExceptionPredicate.isTimeoutException;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.proxy.ProxyConnectException;

class TimeoutExceptionPredicateTest {

    @Test
    void testTimeoutException() {
        assertThat(isTimeoutException(new ConnectTimeoutException())).isTrue();
        assertThat(isTimeoutException(RequestTimeoutException.get())).isTrue();
        assertThat(isTimeoutException(new DnsTimeoutException("test"))).isTrue();
        assertThat(isTimeoutException(new ProxyConnectException("timeout"))).isTrue();
    }

    @Test
    void unwrapUnprocessedException() {
        UnprocessedRequestException unprocessedException =
                UnprocessedRequestException.of(new ConnectTimeoutException());
        assertThat(isTimeoutException(unprocessedException)).isTrue();

        unprocessedException = UnprocessedRequestException.of(new ProxyConnectException("... timeout"));
        assertThat(isTimeoutException(unprocessedException)).isTrue();

        unprocessedException = UnprocessedRequestException.of(new AnticipatedException());
        assertThat(isTimeoutException(unprocessedException)).isFalse();
    }
}
