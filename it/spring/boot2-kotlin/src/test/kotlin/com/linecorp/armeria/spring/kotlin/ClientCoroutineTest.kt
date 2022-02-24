/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.spring.kotlin

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.web.reactive.function.UnsupportedMediaTypeException
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import javax.annotation.PostConstruct
import javax.inject.Inject

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientCoroutineTest {

    @LocalServerPort
    private var port = 0

    @Inject
    private lateinit var connector: ClientHttpConnector

    private lateinit var client: WebClient

    @PostConstruct
    fun setUp() {
        client = WebClient.builder()
            .baseUrl("http://127.0.0.1:$port")
            .clientConnector(connector)
            .build()
    }

    @Test
    fun shouldRaiseWebClientResponseException() {
        runBlocking {
            try {
                client.get()
                    .uri("/abnormal")
                    .retrieve()
                    .awaitBody<Abnormal>()
            } catch (ex: WebClientResponseException) {
                assertThat(ex.rawStatusCode).isEqualTo(200)
                assertThat(ex.cause).isInstanceOf(UnsupportedMediaTypeException::class.java)
                    .hasMessageContaining(
                        "Content type 'text/plain;charset=utf-8' not supported for " +
                        "bodyType=com.linecorp.armeria.spring.kotlin.Abnormal"
                    )
            }
        }
    }
}
