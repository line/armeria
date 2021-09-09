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
 * under the License
 */

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.internal.common.kotlin.FlowCollectingPublisher
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import org.reactivestreams.tck.PublisherVerification
import org.reactivestreams.tck.TestEnvironment
import org.testng.annotations.Test

private val ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))

@Test
internal class FlowCollectingPublisherTest : PublisherVerification<Long>(TestEnvironment(5000L, 2000L)) {
    override fun createPublisher(elements: Long): FlowCollectingPublisher<Long> =
        FlowCollectingPublisher(
            flow {
                (0 until elements).onEach {
                    emit(it)
                }
            },
            ctx
        )

    override fun createFailedPublisher(): FlowCollectingPublisher<Long> =
        FlowCollectingPublisher(
            flow {
                currentCoroutineContext().cancel()
            },
            ctx
        )
}
