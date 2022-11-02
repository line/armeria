/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.testing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.util.concurrent.Future;
import reactor.blockhound.BlockingOperationError;

class Junit5BlockHoundIntegrationTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void testBlockHound() throws Exception {
        final Future<?> future = eventLoop.get().submit(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        assertThatThrownBy(future::get)
                .hasCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    void testBlockingCalls() throws Exception {
        CommonPools.blockingTaskExecutor().submit(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).get();
    }
}
