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

package com.linecorp.armeria.server.kotlin.encoding

import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.encoding.EncodingService
import com.linecorp.armeria.server.encoding.EncodingServiceBuilder

/**
 * Decorates this [HttpService] using an [EncodingService].
 */
fun HttpService.encoding(): EncodingService =
    decorate(EncodingService.newDecorator())

/**
 * Decorates this [HttpService] using an [EncodingService] configured by the given [block].
 */
fun HttpService.encoding(block: EncodingServiceBuilder.() -> Unit): EncodingService =
    decorate(EncodingService.builder().apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using an [EncodingService].
 */
fun ServerBuilder.encoding(): ServerBuilder =
    decorator(EncodingService.newDecorator())

/**
 * Decorates all [HttpService]s using an [EncodingService] configured by the given [block].
 */
fun ServerBuilder.encoding(block: EncodingServiceBuilder.() -> Unit): ServerBuilder =
    decorator(EncodingService.builder().apply(block).newDecorator())
