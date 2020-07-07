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

package example.armeria.grpc.scala

import java.util.{Map => JMap, Set => JSet}

import com.google.common.collect.ImmutableSet
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin
import com.linecorp.armeria.server.docs.{DocServiceFilter, DocServicePlugin, ServiceSpecification}
import com.linecorp.armeria.server.grpc._
import com.linecorp.armeria.server.{Service, ServiceConfig}
import scalapb.GeneratedMessage
import scalapb.json4s.Printer

/**
 * A [[DocServicePlugin]] implementation that supports [[scalapb.GeneratedMessage]] for [[GrpcService]].
 */
class GrpcScalaDocServicePlugin extends DocServicePlugin {

  private val grpcDocServicePlugin = new GrpcDocServicePlugin
  private val printer = new Printer().includingDefaultValueFields

  override def name = "grpc-scala"

  override def supportedServiceTypes(): JSet[Class[_ <: Service[_, _]]] =
    grpcDocServicePlugin.supportedServiceTypes

  override def generateSpecification(serviceConfigs: JSet[ServiceConfig],
                                     filter: DocServiceFilter): ServiceSpecification =
    grpcDocServicePlugin.generateSpecification(serviceConfigs, filter)

  override def loadDocStrings(serviceConfigs: JSet[ServiceConfig]): JMap[String, String] =
    grpcDocServicePlugin.loadDocStrings(serviceConfigs)

  override def supportedExampleRequestTypes: JSet[Class[_]] = ImmutableSet.of(classOf[GeneratedMessage])

  override def serializeExampleRequest(serviceName: String, methodName: String, exampleRequest: Any): String =
    printer.print(exampleRequest.asInstanceOf[GeneratedMessage])
}
