/*
 * Copyright 2024 LINE Corporation
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

import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.Post
import com.linecorp.armeria.server.annotation.ProducesJson
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ValueClassTest {
    @Test
    fun shouldBindValueClass() {
        val client = server.blockingWebClient()
        val input =
            """
            {
                "hello": "world!",
                "hi": 123
            }
            """.trimIndent()
        val res =
            client.prepare()
                .post("/value-class")
                .content(
                    MediaType.JSON,
                    input,
                ).execute()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThatJson(res.contentUtf8()).isEqualTo(input)
    }

    companion object {
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.annotatedService(MyAnnotatedService())
                }
            }

        data class Inner(val hello: String, val hi: Int)

        @JvmInline
        value class MyValueClass(val inner: Inner)

        private class MyAnnotatedService {
            @Post("/value-class")
            @ProducesJson
            suspend fun foo(input: MyValueClass): MyValueClass {
                return input
            }
        }
    }
}
