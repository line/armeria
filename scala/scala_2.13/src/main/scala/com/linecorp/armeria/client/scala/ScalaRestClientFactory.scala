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

package com.linecorp.armeria.client.scala

import com.linecorp.armeria.client.{ClientBuilderParams, ClientFactory, DecoratingClientFactory, WebClient}
import com.linecorp.armeria.common.{Scheme, SerializationFormat}

private[scala] final class ScalaRestClientFactory(delegate: ClientFactory)
    extends DecoratingClientFactory(delegate) {

  override def isClientTypeSupported(clientType: Class[_]): Boolean =
    classOf[ScalaRestClient].isAssignableFrom(clientType)

  override def newClient(params: ClientBuilderParams): ScalaRestClient = {
    val scheme = params.scheme()
    val newParams = ClientBuilderParams.of(
      Scheme.of(SerializationFormat.NONE, scheme.sessionProtocol()),
      params.endpointGroup(),
      params.absolutePathRef(),
      classOf[WebClient],
      params.options())
    val webClient = super.newClient(newParams).asInstanceOf[WebClient]
    ScalaRestClient(webClient)
  }
}
