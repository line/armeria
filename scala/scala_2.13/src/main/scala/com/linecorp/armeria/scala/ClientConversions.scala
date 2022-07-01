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

package com.linecorp.armeria.scala

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.scala.ScalaRestClient
import com.linecorp.armeria.common.annotation.UnstableApi
import scala.language.implicitConversions

@UnstableApi
trait ClientConversions {

  implicit final def restClientOps(webClient: WebClient): RestClientOps =
    new RestClientOps(webClient)
}

final class RestClientOps(private val webClient: WebClient) extends AnyVal {

  /**
   * Returns a `ScalaRestClient` that connects to the same `URI` with this `WebClient`.
   */
  def asScalaRestClient(): ScalaRestClient = ScalaRestClient(webClient)
}
