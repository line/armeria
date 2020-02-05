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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

public class HttpClientIdleTimeoutHandlerTest {

    private static final long idleTimeoutMillis = 100;

    private MockHttpSessionHandler session;
    private EmbeddedChannel ch;

    @Before
    public void before() {
        session = new MockHttpSessionHandler();
        ch = new EmbeddedChannel(new HttpClientIdleTimeoutHandler(idleTimeoutMillis), session);
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
        writeRequest();
        readResponse();
        waitUntilTimeout();
        assertFalse(ch.isOpen());
    }

    @Test
    public void testPendingRequestExists() throws Exception {
        writeRequest();
        Thread.sleep(idleTimeoutMillis * 3 / 2);
        ch.runPendingTasks();
        assertTrue(ch.isOpen());
    }

    @Test
    public void testIdleTimeoutOccurredTwice() throws Exception {
        writeRequest();
        waitUntilTimeout();
        //pending request count is 1
        assertTrue(ch.isOpen());

        readResponse();
        waitUntilTimeout();
        //pending request count turns to 0
        assertFalse(ch.isOpen());
    }

    private void waitUntilTimeout() throws InterruptedException {
        Thread.sleep(idleTimeoutMillis * 3 / 2);
        ch.runPendingTasks();
    }

    private void readResponse() {
        session.unfinishedResponses--;
        final Object res = new Object();
        ch.writeInbound(res);
        assertThat((Object) ch.readInbound()).isEqualTo(res);
    }

    private void writeRequest() {
        session.unfinishedResponses++;
        final Object req = new Object();
        ch.writeOutbound(req);
        assertThat((Object) ch.readOutbound()).isEqualTo(req);
    }

    private static final class MockHttpSessionHandler
            extends ChannelInboundHandlerAdapter implements HttpSession {

        int unfinishedResponses;

        @Override
        public SessionProtocol protocol() {
            return SessionProtocol.H2C;
        }

        @Override
        public boolean canSendRequest() {
            return true;
        }

        @Override
        public InboundTrafficController inboundTrafficController() {
            return InboundTrafficController.disabled();
        }

        @Override
        public int unfinishedResponses() {
            return unfinishedResponses;
        }

        @Override
        public boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retryWithH1C() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deactivate() {
            throw new UnsupportedOperationException();
        }
    }
}
