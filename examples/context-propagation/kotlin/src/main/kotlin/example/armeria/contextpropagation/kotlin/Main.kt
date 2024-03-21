/*
 * Copyright 2020 LINE Corporation
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

package example.armeria.contextpropagation.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server

fun main() {
    val backend =
        Server.builder()
            .service("/square/{num}") { ctx, _ ->
                val num = ctx.pathParam("num")?.toLong()
                if (num != null) {
                    HttpResponse.of((num * num).toString())
                } else {
                    HttpResponse.of(HttpStatus.BAD_REQUEST)
                }
            }
            .http(8081)
            .build()

    val backendClient = WebClient.of("http://localhost:8081")

    val frontend =
        Server.builder()
            .http(8080)
            .serviceUnder("/", MainService(backendClient))
            .build()

    backend.closeOnJvmShutdown()
    frontend.closeOnJvmShutdown()

    backend.start().join()
    frontend.start().join()
}
