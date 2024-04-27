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
 * under the License
 */

package com.linecorp.armeria.server.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@GenerateNativeImageTrace
class DataClassDocServiceTest {
    @Test
    fun dataClassParamSpecification() {
        val client = WebClient.of(server.httpUri()).blocking()
        val jsonNode =
            client.prepare()
                .get("/docs/specification.json")
                .asJson(JsonNode::class.java)
                .execute()
                .content()

        assertThat(jsonNode.get("services")[0]["methods"][0]["parameters"][0]["typeSignature"].asText())
            .isEqualTo("com.linecorp.armeria.server.kotlin.DataClassDocServiceTest\$ExampleQueries1")
        assertThat(jsonNode.get("structs")[0]["name"].asText())
            .isEqualTo("com.linecorp.armeria.server.kotlin.DataClassDocServiceTest\$ExampleQueries1")
        val fields1 = jsonNode.get("structs")[0]["fields"] as ArrayNode
        assertThat(fields1).hasSize(2)
        assertThat(fields1[0]["name"].asText()).isEqualTo("name")
        assertThat(fields1[1]["name"].asText()).isEqualTo("limit")

        assertThat(jsonNode.get("services")[0]["methods"][1]["parameters"][0]["typeSignature"].asText())
            .isEqualTo("com.linecorp.armeria.server.kotlin.DataClassDocServiceTest\$ExampleQueries2")
        assertThat(jsonNode.get("structs")[1]["name"].asText())
            .isEqualTo("com.linecorp.armeria.server.kotlin.DataClassDocServiceTest\$ExampleQueries2")
        val fields2 = jsonNode.get("structs")[1]["fields"] as ArrayNode
        assertThat(fields2).hasSize(3)
        assertThat(fields2[0]["name"].asText()).isEqualTo("application")
        assertThat(fields2[1]["name"].asText()).isEqualTo("topic")
        assertThat(fields2[2]["name"].asText()).isEqualTo("group")
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.annotatedService()
                        .requestConverters()
                    sb.annotatedService(MyKotlinService())
                    sb.serviceUnder("/docs", DocService())
                }
            }
    }

    class MyKotlinService {
        @Get("/example1")
        fun getIdV1(
            @Suppress("UNUSED_PARAMETER") queries: ExampleQueries1,
        ): String {
            return "example"
        }

        @Get("/example2")
        fun getIdV2(
            @Suppress("UNUSED_PARAMETER") queries: ExampleQueries2,
        ): String {
            return "example"
        }
    }

    data class ExampleQueries1(
        @Param
        val name: String,
        @Param @Default
        val limit: Int?,
    )

    data class ExampleQueries2(
        @Param
        val application: String,
        @Param
        val topic: String,
        @Param
        val group: String,
    )
}
