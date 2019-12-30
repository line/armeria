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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.EventLoop;

class TimeoutControllerTest {

    static {
        // call workerGroup early to avoid lazy initialization
        CommonPools.workerGroup();
    }

    @BeforeEach
    void setUp() {
    }

    @Test
    void shouldCallInitTimeout() {
        TimeoutController timeoutController = new TimeoutController(timeoutTask, eventLoopSupplier) {

            @Override
            protected EventLoop eventLoop() {
                return CommonPools.workerGroup().next();
            }

            @Override
            protected boolean isReady() {
                return true;
            }

            @Override
            protected boolean isDone() {
                return false;
            }

            @Override
            protected void onTimeout() {
            }
        };

        assertThatThrownBy(() -> timeoutController.adjustTimeout(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initTimeout(timeoutMillis) is not called yet");
        timeoutController.initTimeout(100);
        timeoutController.adjustTimeout(10);
        assertThat(timeoutController.timeoutMillis()).isBetween(110L, 115L);
    }

    @Test
    void testCommonWorkGroupNext() {
        Flags.numCommonWorkers();
        long start = System.nanoTime();
        System.out.println("start = " + start);
        System.out.println(
                "System.nanoTime() - start = " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        start = System.nanoTime();
        System.out.println("start = " + start);
        CommonPools.workerGroup().next();
        System.out.println(
                "System.nanoTime() - start = " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}