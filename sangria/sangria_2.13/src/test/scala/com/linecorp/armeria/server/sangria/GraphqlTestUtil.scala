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

package com.linecorp.armeria.server.sangria

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.{AggregatedHttpResponse, HttpMethod, MediaType, QueryParams}
import com.linecorp.armeria.internal.server.JacksonUtil
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object GraphqlTestUtil {

  private val mapper = JacksonUtil.newDefaultObjectMapper()

  def executeQuery(
      client: WebClient,
      method: HttpMethod,
      path: String = "/graphql",
      query: String,
      variables: Map[String, Any] = Map.empty): AggregatedHttpResponse = {
    if (method == HttpMethod.GET) {
      val graphql = QueryParams
        .builder()
        .add("query", query)
        .add("variables", mapper.writeValueAsString(variables))
        .build()
      client.get(s"$path?${graphql.toQueryString()}").aggregate().join()
    } else {
      val graphql =
        if (variables.isEmpty) {
          mapper.writeValueAsString(Map("query" -> query))
        } else {
          mapper.writeValueAsString(Map("query" -> query, "variables" -> variables))
        }

      client
        .prepare()
        .post(path)
        .content(MediaType.JSON, graphql)
        .execute()
        .aggregate()
        .join()
    }
  }
}
