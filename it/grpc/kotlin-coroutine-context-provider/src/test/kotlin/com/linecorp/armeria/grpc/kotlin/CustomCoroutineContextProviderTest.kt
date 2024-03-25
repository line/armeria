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
 * under the License.
 */

package com.linecorp.armeria.grpc.kotlin

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import testing.grpc.Hello
import testing.grpc.TestServiceGrpcKt

class CustomCoroutineContextProviderTest {
    companion object {
        @JvmField
        @RegisterExtension
        val server =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.service(
                        GrpcService.builder()
                            .addService(TestServiceImpl())
                            .build(),
                    )
                }
            }
    }

    @Test
    fun shouldExecuteServiceInCustomDispatcher() {
        runBlocking {
            val client =
                GrpcClients.newClient(server.httpUri(), TestServiceGrpcKt.TestServiceCoroutineStub::class.java)
            GrpcClients.builder(server.httpUri())
                .intercept()
            val response = client.hello(Hello.HelloRequest.newBuilder().setName("Armeria").build())
            assertThat(response.message).startsWith("Executed in custom-kotlin-grpc-worker")
        }
    }
}
