/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.testing.junit.common.EventLoopGroupExtension;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class EventLoopThreadTest {

    @RegisterExtension
    public static final EventLoopGroupExtension eventLoop = new EventLoopGroupExtension(1);

    @Test
    void reactorNonBlocking() {
        eventLoop.get().next().submit(() -> {
            assertThat(Schedulers.isInNonBlockingThread()).isTrue();

            final String[] test = new String[] {"1", "2", "3", "4", "5"};
            assertThatThrownBy(() -> Flux.fromArray(test).blockFirst())
                    .isInstanceOf(IllegalStateException.class);
        }).syncUninterruptibly();
    }
}
