/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;

public class MaxConnectionHandlerTest {
    @Test
    public void testCountAndDenyConnection() {
        MaxConnectionHandler handler = new MaxConnectionHandler(1);

        EmbeddedChannel ch1 = new EmbeddedChannel(handler);
        assertEquals(1, handler.getCurrentConnections());
        assertTrue(ch1.isActive());

        EmbeddedChannel ch2 = new EmbeddedChannel(handler);
        assertEquals(1, handler.getCurrentConnections());
        assertFalse(ch2.isActive());

        ch1.close();
        assertEquals(0, handler.getCurrentConnections());

        assertFalse(ch1.finish());
        assertFalse(ch2.finish());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxConnectionsRange1() {
        new MaxConnectionHandler(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxConnectionsRange2() {
        new MaxConnectionHandler(-1);
    }

    @Test
    public void testMaxConnectionsRange3() {
        MaxConnectionHandler handler = new MaxConnectionHandler(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, handler.getMaxConnections());
    }
}
