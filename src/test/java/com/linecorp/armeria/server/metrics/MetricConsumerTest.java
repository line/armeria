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

package com.linecorp.armeria.server.metrics;

import static org.junit.Assert.assertEquals;

import java.util.Optional;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import org.junit.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metrics.MetricConsumer;

public class MetricConsumerTest {
    @Test
    public void testInfiniteRecursion() throws Exception {
        final int[] executeCounter = { 0 };

        MetricConsumer consumer = (a, b, c, d, e, f, g, h) -> executeCounter[0] += 1;
        consumer.andThen(consumer).invocationComplete(
                Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP), 200, 0, 0, 0, "", "",
                Optional.of(""));

        assertEquals("invocationComplete should be executed twice", 2, executeCounter[0]);
    }
}

