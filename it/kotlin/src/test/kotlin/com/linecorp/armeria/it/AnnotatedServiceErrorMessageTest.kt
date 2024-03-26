/*
 *  Copyright 2020 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.it

import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.Get
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AnnotatedServiceErrorMessageTest {
    @Test
    fun test() {
        val serverBuilder: ServerBuilder =
            Server.builder()
                .annotatedService(MyAnnotatedService())

        assertThatThrownBy { serverBuilder.build() }
            .hasMessageContaining(
                "Kotlin suspending functions are supported" +
                    " only when you added 'armeria-kotlin' as a dependency.",
            )
    }

    companion object {
        private class MyAnnotatedService {
            @Get("/foo")
            suspend fun foo(): String = "foo"
        }
    }
}
