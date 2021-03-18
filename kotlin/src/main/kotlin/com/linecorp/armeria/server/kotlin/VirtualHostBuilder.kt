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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.VirtualHost
import com.linecorp.armeria.server.VirtualHostBuilder

/**
 * Configures the default [VirtualHost] with the given [block].
 */
fun ServerBuilder.defaultVirtualHost(block: VirtualHostBuilder.() -> Unit): ServerBuilder =
    defaultVirtualHost().apply(block).and()

/**
 * Configures a [VirtualHost] with the given [hostnamePattern] and [block].
 */
fun ServerBuilder.virtualHost(hostnamePattern: String, block: VirtualHostBuilder.() -> Unit): ServerBuilder =
    virtualHost(hostnamePattern).apply(block).and()

/**
 * Configures a [VirtualHost] with the given [defaultHostname], [hostnamePattern] and [block].
 */
fun ServerBuilder.virtualHost(
    defaultHostname: String,
    hostnamePattern: String,
    block: VirtualHostBuilder.() -> Unit
): ServerBuilder = virtualHost(defaultHostname, hostnamePattern).apply(block).and()
