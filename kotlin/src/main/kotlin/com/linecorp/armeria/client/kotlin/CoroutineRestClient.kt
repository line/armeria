/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.kotlin

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.RestClientPreparation
import com.linecorp.armeria.common.JacksonObjectMapperProvider
import com.linecorp.armeria.common.ResponseEntity
import kotlinx.coroutines.future.await

/**
 * Sends the HTTP request and converts the JSON response body as the `T` object using the default
 * [ObjectMapper].
 *
 * @see JacksonObjectMapperProvider
 */
suspend inline fun <reified T : Any> RestClientPreparation.execute(): ResponseEntity<T> {
    return execute(object : TypeReference<T>() {}).await()!!
}

/**
 * Sends the HTTP request and converts the JSON response body as the `T` object using the specified
 * [ObjectMapper].
 */
suspend inline fun <reified T : Any> RestClientPreparation.execute(mapper: ObjectMapper): ResponseEntity<T> {
    return execute(object : TypeReference<T>() {}, mapper).await()!!
}
