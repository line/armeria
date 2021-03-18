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

import com.linecorp.armeria.common.encoding.StreamDecoderFactory
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.encoding.DecodingService

/**
 * Decorates this [HttpService] using a [DecodingService].
 */
fun HttpService.decoding(): DecodingService =
    decorate(DecodingService.newDecorator())

/**
 * Decorates this [HttpService] using a [DecodingService] with the given [decoderFactories].
 */
fun HttpService.decoding(vararg decoderFactories: StreamDecoderFactory): DecodingService =
    decorate(DecodingService.newDecorator(*decoderFactories))

/**
 * Decorates this [HttpService] using a [DecodingService] with the given [decoderFactories].
 */
fun HttpService.decoding(decoderFactories: Iterable<StreamDecoderFactory>): DecodingService =
    decorate(DecodingService.newDecorator(decoderFactories))

/**
 * Decorates all [HttpService]s using a [DecodingService].
 */
fun ServerBuilder.decoding(): ServerBuilder =
    decorator(DecodingService.newDecorator())

/**
 * Decorates all [HttpService]s using a [DecodingService] with the given [decoderFactories].
 */
fun ServerBuilder.decoding(vararg decoderFactories: StreamDecoderFactory): ServerBuilder =
    decorator(DecodingService.newDecorator(*decoderFactories))

/**
 * Decorates all [HttpService]s using a [DecodingService] with the given [decoderFactories].
 */
fun ServerBuilder.decoding(decoderFactories: Iterable<StreamDecoderFactory>): ServerBuilder =
    decorator(DecodingService.newDecorator(decoderFactories))
