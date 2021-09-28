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

package com.linecorp.armeria.internal.common.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.ProducesJsonSequences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import java.util.stream.IntStream

@State(Scope.Benchmark)
@Warmup(iterations = 1)
@Suppress("unused")
open class AnnotatedServiceFlowBenchmark {

    lateinit var server: Server
    lateinit var client: WebClient

    @Setup
    open fun setup() {
        server = Server.builder()
            .annotatedService("/benchmark", object {
                @Get("/flow")
                @ProducesJsonSequences
                fun flowBm(): Flow<String> = flow {
                    (0 until 1000).forEach {
                        emit("$it")
                    }
                }

                @Get("/publisher")
                @ProducesJsonSequences
                fun publisherBm(): Publisher<String> =
                    Flux.fromStream(IntStream.range(0, 1000).mapToObj { it.toString() })
            })
            .build()
            .also { it.start().join() }

        client = WebClient.of("h2c://127.0.0.1:${server.activeLocalPort()}")
    }

    @TearDown
    open fun stopServer() {
        server.stop().join()
    }

    @Benchmark
    open fun flowReturnType(bh: Blackhole) {
        bh.consume(client.get("/benchmark/flow").aggregate().join())
    }

    @Benchmark
    open fun publisherReturnType(bh: Blackhole) {
        bh.consume(client.get("/benchmark/publisher").aggregate().join())
    }
}
