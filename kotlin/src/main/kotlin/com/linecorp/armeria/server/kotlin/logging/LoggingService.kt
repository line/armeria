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

package com.linecorp.armeria.server.kotlin.logging

import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.server.logging.LoggingServiceBuilder

/**
 * Decorates this [HttpService] using a [LoggingService].
 */
fun HttpService.logging(): LoggingService =
    decorate(LoggingService.newDecorator())

/**
 * Decorates this [HttpService] using a [LoggingService] configured by the given [block].
 */
fun HttpService.logging(block: LoggingServiceBuilder.() -> Unit): LoggingService =
    decorate(LoggingService.builder().apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using a [LoggingService].
 */
fun ServerBuilder.logging(): ServerBuilder =
    decorator(LoggingService.newDecorator())

/**
 * Decorates all [HttpService]s using a [LoggingService] configured by the given [block].
 */
fun ServerBuilder.logging(block: LoggingServiceBuilder.() -> Unit): ServerBuilder =
    decorator(LoggingService.builder().apply(block).newDecorator())
