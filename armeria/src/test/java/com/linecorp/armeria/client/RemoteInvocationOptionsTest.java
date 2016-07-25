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

import static com.linecorp.armeria.client.RemoteInvokerOption.CONNECT_TIMEOUT;
import static com.linecorp.armeria.client.RemoteInvokerOption.EVENT_LOOP_GROUP;
import static com.linecorp.armeria.client.RemoteInvokerOption.IDLE_TIMEOUT;
import static com.linecorp.armeria.client.RemoteInvokerOption.MAX_CONCURRENCY;
import static com.linecorp.armeria.client.RemoteInvokerOption.MAX_FRAME_LENGTH;
import static com.linecorp.armeria.client.RemoteInvokerOption.TRUST_MANAGER_FACTORY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.TrustManagerFactory;

import org.junit.Test;

import io.netty.channel.EventLoop;

public class RemoteInvocationOptionsTest {

    @Test
    public void defaultTest() {
        RemoteInvokerOptions options = RemoteInvokerOptions.DEFAULT;
        assertThat(options.connectTimeout(), is(notNullValue()));
        assertThat(options.idleTimeout(), is(notNullValue()));
        assertThat(options.trustManagerFactory(), is(Optional.empty()));
        assertThat(options.maxFrameLength(), greaterThan(0));
        assertThat(options.maxConcurrency(), greaterThan(0));
    }

    @Test
    public void valueOverrideTest(){
        Duration connectionTimeout = Duration.ofMillis(10);
        Duration idleTimeout = Duration.ofMillis(200);
        EventLoop eventLoop = mock(EventLoop.class);
        TrustManagerFactory trustManagerFactory = mock(TrustManagerFactory.class);

        Integer maxFrameLength = 100;
        Integer maxConcurrency = 1;
        RemoteInvokerOptions options = RemoteInvokerOptions.of(
                CONNECT_TIMEOUT.newValue(connectionTimeout),
                IDLE_TIMEOUT.newValue(idleTimeout),
                EVENT_LOOP_GROUP.newValue(eventLoop),
                TRUST_MANAGER_FACTORY.newValue(trustManagerFactory),
                MAX_FRAME_LENGTH.newValue(maxFrameLength),
                MAX_CONCURRENCY.newValue(maxConcurrency)
        );

        assertThat(options.get(CONNECT_TIMEOUT),is(Optional.of(connectionTimeout)));
        assertThat(options.get(IDLE_TIMEOUT),is(Optional.of(idleTimeout)));
        assertThat(options.get(EVENT_LOOP_GROUP),is(Optional.of(eventLoop)));
        assertThat(options.get(MAX_FRAME_LENGTH),is(Optional.of(maxFrameLength)));
        assertThat(options.get(MAX_CONCURRENCY), is(Optional.of(maxConcurrency)));

    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateFailConnectTimeout(){
        RemoteInvokerOptions.of(CONNECT_TIMEOUT.newValue(Duration.ZERO));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateFailIdleTimeout(){
        RemoteInvokerOptions.of(IDLE_TIMEOUT.newValue(Duration.ofMillis(-1)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateMaxFrameLength(){
        RemoteInvokerOptions.of(MAX_FRAME_LENGTH.newValue(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateMaxConcurrency(){
        RemoteInvokerOptions.of(MAX_CONCURRENCY.newValue(0));
    }
}

