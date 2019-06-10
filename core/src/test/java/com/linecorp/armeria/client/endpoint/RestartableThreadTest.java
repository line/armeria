/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class RestartableThreadTest {

    @Rule
    public final TestName testName = new TestName();

    @Test
    public void testRestartableThreadRestartBehavior() {
        final RestartableThread restartableThread =
                new RestartableThread(testName.getMethodName(), () -> () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.yield();
                    }
                });

        restartableThread.start();
        assertThat(restartableThread.isRunning()).isTrue();
        restartableThread.stop();
        assertThat(restartableThread.isRunning()).isFalse();
        restartableThread.start();
        assertThat(restartableThread.isRunning()).isTrue();
        restartableThread.stop();
        assertThat(restartableThread.isRunning()).isFalse();
    }
}
