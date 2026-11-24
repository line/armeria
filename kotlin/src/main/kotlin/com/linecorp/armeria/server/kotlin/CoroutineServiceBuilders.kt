/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.server.Route
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.VirtualHostBuilder

/**
 * Binds the specified [CoroutineHttpService] at the specified path pattern of the default
 * [com.linecorp.armeria.server.VirtualHost].
 */
fun ServerBuilder.coroutineService(
    pathPattern: String,
    service: CoroutineHttpService,
): ServerBuilder = service(pathPattern, service)

/**
 * Binds the specified [CoroutineHttpService] at the specified [Route] of the default
 * [com.linecorp.armeria.server.VirtualHost].
 */
fun ServerBuilder.coroutineService(
    route: Route,
    service: CoroutineHttpService,
): ServerBuilder = service(route, service)

/**
 * Binds the specified [CoroutineHttpService] under the specified directory of the default
 * [com.linecorp.armeria.server.VirtualHost].
 */
fun ServerBuilder.coroutineServiceUnder(
    pathPrefix: String,
    service: CoroutineHttpService,
): ServerBuilder = serviceUnder(pathPrefix, service)

/**
 * Binds the specified [CoroutineHttpService] at the specified path pattern.
 */
fun VirtualHostBuilder.coroutineService(
    pathPattern: String,
    service: CoroutineHttpService,
): VirtualHostBuilder = service(pathPattern, service)

/**
 * Binds the specified [CoroutineHttpService] at the specified [Route].
 */
fun VirtualHostBuilder.coroutineService(
    route: Route,
    service: CoroutineHttpService,
): VirtualHostBuilder = service(route, service)

/**
 * Binds the specified [CoroutineHttpService] under the specified directory.
 */
fun VirtualHostBuilder.coroutineServiceUnder(
    pathPrefix: String,
    service: CoroutineHttpService,
): VirtualHostBuilder = serviceUnder(pathPrefix, service)
