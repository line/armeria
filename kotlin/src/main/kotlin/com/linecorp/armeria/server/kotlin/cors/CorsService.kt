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

package com.linecorp.armeria.server.kotlin.cors

import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.cors.CorsServiceBuilder

/**
 * Decorates this [HttpService] using a [CorsService] configured by the given [block] with its origin set with
 * `*` (any origin)
 */
fun HttpService.corsForAnyOrigin(block: CorsServiceBuilder.() -> Unit): CorsService =
    decorate(CorsService.builderForAnyOrigin().apply(block).newDecorator())

/**
 * Decorates this [HttpService] using a [CorsService] configured by the given [block] with the given [origins].
 */
fun HttpService.cors(vararg origins: String, block: CorsServiceBuilder.() -> Unit): CorsService =
    decorate(CorsService.builder(*origins).apply(block).newDecorator())

/**
 * Decorates this [HttpService] using a [CorsService] configured by the given [block] with the given [origins].
 */
fun HttpService.cors(origins: Iterable<String>, block: CorsServiceBuilder.() -> Unit): CorsService =
    decorate(CorsService.builder(origins).apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using a [CorsService] configured by the given [block] with its origin set with
 * `*` (any origin)
 */
fun ServerBuilder.corsForAnyOrigin(block: CorsServiceBuilder.() -> Unit): ServerBuilder =
    decorator(CorsService.builderForAnyOrigin().apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using a [CorsService] configured by the given [block] with the given [origins].
 */
fun ServerBuilder.cors(vararg origins: String, block: CorsServiceBuilder.() -> Unit): ServerBuilder =
    decorator(CorsService.builder(*origins).apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using a [CorsService] configured by the given [block] with the given [origins].
 */
fun ServerBuilder.cors(origins: Iterable<String>, block: CorsServiceBuilder.() -> Unit): ServerBuilder =
    decorator(CorsService.builder(origins).apply(block).newDecorator())
