/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.SessionOption.CONNECT_TIMEOUT;
import static com.linecorp.armeria.client.SessionOption.EVENT_LOOP_GROUP;
import static com.linecorp.armeria.client.SessionOption.IDLE_TIMEOUT;
import static com.linecorp.armeria.client.SessionOption.TRUST_MANAGER_FACTORY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.TrustManagerFactory;

import org.junit.Test;

import io.netty.channel.EventLoop;

public class SessionOptionsTest {

    @Test
    public void defaultTest() {
        SessionOptions options = SessionOptions.DEFAULT;
        assertThat(options.connectTimeout(), is(notNullValue()));
        assertThat(options.idleTimeout(), is(notNullValue()));
        assertThat(options.trustManagerFactory(), is(Optional.empty()));
    }

    @Test
    public void valueOverrideTest(){
        Duration connectionTimeout = Duration.ofMillis(10);
        Duration idleTimeout = Duration.ofMillis(200);
        EventLoop eventLoop = mock(EventLoop.class);
        TrustManagerFactory trustManagerFactory = mock(TrustManagerFactory.class);

        Integer maxConcurrency = 1;
        SessionOptions options = SessionOptions.of(
                CONNECT_TIMEOUT.newValue(connectionTimeout),
                IDLE_TIMEOUT.newValue(idleTimeout),
                EVENT_LOOP_GROUP.newValue(eventLoop),
                TRUST_MANAGER_FACTORY.newValue(trustManagerFactory)
        );

        assertThat(options.get(CONNECT_TIMEOUT),is(Optional.of(connectionTimeout)));
        assertThat(options.get(IDLE_TIMEOUT),is(Optional.of(idleTimeout)));
        assertThat(options.get(EVENT_LOOP_GROUP),is(Optional.of(eventLoop)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateFailConnectTimeout(){
        SessionOptions.of(CONNECT_TIMEOUT.newValue(Duration.ZERO));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateFailIdleTimeout(){
        SessionOptions.of(IDLE_TIMEOUT.newValue(Duration.ofMillis(-1)));
    }
}

