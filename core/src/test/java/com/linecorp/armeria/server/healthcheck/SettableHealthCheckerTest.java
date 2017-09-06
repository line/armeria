/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.server.healthcheck;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SettableHealthCheckerTest {

    @Test
    public void justCreated() {
        SettableHealthChecker checker = new SettableHealthChecker();
        assertFalse(checker.isHealthy());
    }

    @Test
    public void setHealthy() {
        SettableHealthChecker checker = new SettableHealthChecker();
        checker.setHealthy(true);
        assertTrue(checker.isHealthy());
    }

    @Test
    public void setUnHealthy() {
        SettableHealthChecker checker = new SettableHealthChecker();
        checker.setHealthy(false);
        assertFalse(checker.isHealthy());
    }
}
