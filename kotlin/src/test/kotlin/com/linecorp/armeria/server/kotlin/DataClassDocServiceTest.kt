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
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.Default
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DataClassDocServiceTest {

    @Test
    fun dataClassParamSpecification() {
        val client = WebClient.of(server.httpUri()).blocking()
        val jsonNode = client.prepare()
            .get("/docs/specification.json")
            .asJson(JsonNode::class.java)
            .execute()
            .content()

        assertThat(jsonNode.get("services")[0]["methods"][0]["parameters"][0]["typeSignature"].asText())
            .isEqualTo("com.linecorp.armeria.server.kotlin.ExampleQueries")
        assertThat(jsonNode.get("structs")[0]["name"].asText())
            .isEqualTo("com.linecorp.armeria.server.kotlin.ExampleQueries")
        val fields = jsonNode.get("structs")[0]["fields"] as ArrayNode
        assertThat(fields).hasSize(2)
        assertThat(fields[0]["name"].asText()).isEqualTo("name")
        assertThat(fields[1]["name"].asText()).isEqualTo("limit")
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                sb.annotatedService()
                    .requestConverters()
                sb.annotatedService(MyKotlinService())
                sb.serviceUnder("/docs", DocService())
            }
        }
    }
}

class MyKotlinService {
    @Get("/example")
    fun getId(@Suppress("UNUSED_PARAMETER") queries: ExampleQueries): String {
        return "example"
    }
}

data class ExampleQueries(
    @Param
    val name: String,

    @Param @Default
    val limit: Int?
)

data class ExampleBody(val name: String, val limit: Int?)
