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

package com.linecorp.armeria.server.kotlin.throttling

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.throttling.ThrottlingRejectHandler
import com.linecorp.armeria.server.throttling.ThrottlingService
import com.linecorp.armeria.server.throttling.ThrottlingServiceBuilder
import com.linecorp.armeria.server.throttling.ThrottlingStrategy

/**
 * Decorates this [HttpService] using a [ThrottlingService] with the given [strategy].
 */
fun HttpService.throttling(strategy: ThrottlingStrategy<HttpRequest>): ThrottlingService =
    decorate(ThrottlingService.newDecorator(strategy))

/**
 * Decorates this [HttpService] using a [ThrottlingService] with the given [strategy] and [rejectHandler].
 */
fun HttpService.throttling(
    strategy: ThrottlingStrategy<HttpRequest>,
    rejectHandler: ThrottlingRejectHandler<HttpRequest, HttpResponse>
): ThrottlingService = decorate(ThrottlingService.newDecorator(strategy, rejectHandler))

/**
 * Decorates this [HttpService] using a [ThrottlingService] configured by the given [block] with the given
 * [strategy].
 */
fun HttpService.throttling(
    strategy: ThrottlingStrategy<HttpRequest>,
    block: ThrottlingServiceBuilder.() -> Unit
): ThrottlingService = decorate(ThrottlingService.builder(strategy).apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using a [ThrottlingService] with the given [strategy].
 */
fun ServerBuilder.throttling(strategy: ThrottlingStrategy<HttpRequest>): ServerBuilder =
    decorator(ThrottlingService.newDecorator(strategy))

/**
 * Decorates all [HttpService]s using a [ThrottlingService] with the given [strategy] and [rejectHandler].
 */
fun ServerBuilder.throttling(
    strategy: ThrottlingStrategy<HttpRequest>,
    rejectHandler: ThrottlingRejectHandler<HttpRequest, HttpResponse>
): ServerBuilder = decorator(ThrottlingService.newDecorator(strategy, rejectHandler))

/**
 * Decorates all [HttpService]s using a [ThrottlingService] configured by the given [block] with the given
 * [strategy].
 */
fun ServerBuilder.throttling(
    strategy: ThrottlingStrategy<HttpRequest>,
    block: ThrottlingServiceBuilder.() -> Unit
): ServerBuilder = decorator(ThrottlingService.builder(strategy).apply(block).newDecorator())
