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

package com.linecorp.armeria.server.kotlin.auth

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.auth.AuthService
import com.linecorp.armeria.server.auth.AuthServiceBuilder
import com.linecorp.armeria.server.auth.Authorizer

/**
 * Decorates this [HttpService] using an [AuthService] with the given [authorizers].
 */
fun HttpService.authorizing(vararg authorizers: Authorizer<HttpRequest>): AuthService =
    decorate(AuthService.newDecorator(*authorizers))

/**
 * Decorates this [HttpService] using an [AuthService] with the given [authorizers].
 */
fun HttpService.authorizing(authorizers: Iterable<Authorizer<HttpRequest>>): AuthService =
    decorate(AuthService.newDecorator(authorizers))

/**
 * Decorates this [HttpService] using an [AuthService] configured by the given [block].
 */
fun HttpService.authorizing(block: AuthServiceBuilder.() -> Unit): AuthService =
    decorate(AuthService.builder().apply(block).newDecorator())

/**
 * Decorates all [HttpService]s using an [AuthService] with the [authorizers].
 */
fun ServerBuilder.authorizing(vararg authorizers: Authorizer<HttpRequest>): ServerBuilder =
    decorator(AuthService.newDecorator(*authorizers))

/**
 * Decorates all [HttpService]s using an [AuthService] with the [authorizers].
 */
fun ServerBuilder.authorizing(authorizers: Iterable<Authorizer<HttpRequest>>): ServerBuilder =
    decorator(AuthService.newDecorator(authorizers))

/**
 * Decorates all [HttpService]s using an [AuthService] configured by the given [block].
 */
fun ServerBuilder.authorizing(block: AuthServiceBuilder.() -> Unit): ServerBuilder =
    decorator(AuthService.builder().apply(block).newDecorator())
