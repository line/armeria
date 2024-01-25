/*
 * Copyright 2023 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.common.kotlin

import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@GenerateNativeImageTrace
class CoroutineRequestContextTest {
    @Test
    fun serviceRequestContext() {
        val ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build()
        runTest(ctx.asCoroutineContext()) {
            assertThat(ServiceRequestContext.current()).isSameAs(ctx)
            withContext(Dispatchers.Default) {
                assertThat(ServiceRequestContext.current()).isSameAs(ctx)
            }
        }
    }

    @Test
    fun clientRequestContext() {
        val ctx = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build()
        runTest(ctx.asCoroutineContext()) {
            assertThat(ClientRequestContext.current()).isSameAs(ctx)
            withContext(Dispatchers.Default) {
                assertThat(ClientRequestContext.current()).isSameAs(ctx)
            }
        }
    }
}
