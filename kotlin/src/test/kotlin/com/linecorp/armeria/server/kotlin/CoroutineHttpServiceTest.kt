/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CoroutineHttpServiceTest {
    companion object {
        @JvmField
        @RegisterExtension
        val server =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.service(
                        "/hello",
                        CoroutineHttpService { ctx, req ->
                            assertContextPropagation()
                            HttpResponse.of("hello world")
                        },
                    ).decorator(
                        CoroutineContextService.newDecorator { ctx ->
                            CoroutineName("my-coroutine-name")
                        },
                    )
                }
            }

        private suspend fun assertContextPropagation() {
            assertThat(ServiceRequestContext.currentOrNull()).isNotNull()
            assertThat(currentCoroutineContext()[CoroutineName]?.name).isEqualTo("my-coroutine-name")
        }
    }

    @Test
    fun `Should return hello world when call hello coroutine service`() =
        runTest {
            val response = server.blockingWebClient().get("/hello")
            assertThat(response.status()).isEqualTo(HttpStatus.OK)
            assertThat(response.contentUtf8()).isEqualTo("hello world")
        }
}
