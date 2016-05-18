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

package com.linecorp.armeria.client.circuitbreaker;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

import org.junit.Test;

public class EventCountTest {

    @Test
    public void testCounts() {
        assertThat(new EventCount(0, 0).success(), is(0L));
        assertThat(new EventCount(1, 0).success(), is(1L));
        assertThat(new EventCount(1, 1).success(), is(1L));
        assertThat(new EventCount(0, 0).failure(), is(0L));
        assertThat(new EventCount(0, 1).failure(), is(1L));
        assertThat(new EventCount(1, 1).failure(), is(1L));
        assertThat(new EventCount(0, 0).total(), is(0L));
        assertThat(new EventCount(1, 1).total(), is(2L));
    }

    @Test
    public void testRates() {
        try {
            new EventCount(0, 0).successRate();
            fail();
        } catch (ArithmeticException e) {
            // Expected
        }
        assertThat(new EventCount(1, 0).successRate(), is(1.0));
        assertThat(new EventCount(1, 1).successRate(), is(0.5));

        try {
            new EventCount(0, 0).failureRate();
            fail();
        } catch (ArithmeticException e) {
            // Expected
        }
        assertThat(new EventCount(0, 1).failureRate(), is(1.0));
        assertThat(new EventCount(1, 1).failureRate(), is(0.5));
    }

    @Test
    public void testInvalidArguments() {
        try {
            new EventCount(-1, 0);
            fail();
        } catch (AssertionError e) {
            // Expected
        }
        try {
            new EventCount(0, -1);
            fail();
        } catch (AssertionError e) {
            // Expected
        }
    }

    @Test
    public void testEquals() {
        EventCount ec = new EventCount(1, 1);
        assertThat(ec, is(ec));
        assertThat(new EventCount(0, 0).equals(new EventCount(0, 0)), is(true));
        assertThat(new EventCount(1, 0).equals(new EventCount(0, 0)), is(false));
        assertThat(new EventCount(1, 0).equals(new Object()), is(false));
    }
}
