/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

public class HttpServerIdleTimeoutHandlerTest {

    private static final long idleTimeoutMillis = 100;

    private MockHttpServerHandler server;
    private EmbeddedChannel ch;

    @Before
    public void before() {
        server = new MockHttpServerHandler();
        ch = new EmbeddedChannel(new HttpServerIdleTimeoutHandler(idleTimeoutMillis), server);
        assertTrue(ch.isOpen());
    }

    @After
    public void after() {
        assertFalse(ch.finish());
    }

    @Test
    public void testIdleTimeoutWithoutRequest() throws Exception {
        waitUntilTimeout();
        assertFalse(ch.isOpen());
    }

    @Test
    public void testIdleTimeout() throws Exception {
        readRequest();
        writeResponse();
        waitUntilTimeout();
        assertFalse(ch.isOpen());
    }

    @Test
    public void testPendingRequestExists() throws Exception {
        readRequest();
        Thread.sleep(idleTimeoutMillis);
        ch.runPendingTasks();
        assertTrue(ch.isOpen());
    }

    @Test
    public void testIdleTimeoutOccurredTwice() throws Exception {
        readRequest();
        waitUntilTimeout();
        //pending request count is 2
        assertTrue(ch.isOpen());

        writeResponse();
        //pending request count turns to 0
        waitUntilTimeout();
        assertFalse(ch.isOpen());
    }

    private void waitUntilTimeout() throws InterruptedException {
        Thread.sleep(idleTimeoutMillis * 3 / 2);
        ch.runPendingTasks();
    }

    private void readRequest() {
        final Object msg = new Object();
        ch.writeInbound(msg);
        assertThat((Object) ch.readInbound()).isEqualTo(msg);
        server.unfinishedRequests++;
    }

    private void writeResponse() {
        final Object msg = new Object();
        ch.writeOutbound(msg);
        assertThat((Object) ch.readOutbound()).isEqualTo(msg);
        server.unfinishedRequests--;
    }

    private static final class MockHttpServerHandler
            extends ChannelInboundHandlerAdapter implements HttpServer {

        int unfinishedRequests;

        @Override
        public SessionProtocol protocol() {
            return SessionProtocol.H2C;
        }

        @Override
        public int unfinishedRequests() {
            return unfinishedRequests;
        }
    }
}
