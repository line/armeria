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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.common.MyThreadLocalAccessorProvider.MyThreadLocalAccessor;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

class ContextPropagationFlagTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void basicCase() throws InterruptedException {
        final RequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final AtomicReference<String> atomicRef = new AtomicReference<>();
        final String armeria = "armeria";

        MyThreadLocalAccessor.THREAD_LOCAL.set(armeria);

        ctx.makeContextAware(eventLoop.get()).execute(() -> {
            atomicRef.set(MyThreadLocalAccessor.THREAD_LOCAL.get());
        });

        await().untilAsserted(() -> assertThat(atomicRef).doesNotHaveNullValue());
        assertThat(atomicRef).hasValue(armeria);
    }
}
