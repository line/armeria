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

package com.linecorp.armeria.server.scalapb

import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.server.protobuf.ProtobufNamedTypeInfoProvider
import com.linecorp.armeria.server.docs.{NamedTypeInfo, NamedTypeInfoProvider}
import com.linecorp.armeria.server.scalapb.ScalaPbConverterUtil.isProtobufMessage

/**
 * A `NamedTypeInfoProvider` to create a `NamedTypeInfo` from a ScalaPB `GeneratedMessage`.
 */
@UnstableApi
final class ScalaPbNamedTypeInfoProvider extends NamedTypeInfoProvider {
  override def newNamedTypeInfo(typeDescriptor: Any): NamedTypeInfo = {
    typeDescriptor match {
      case clazz: Class[_] if isProtobufMessage(clazz) =>
        val message = ScalaPbRequestConverterFunction.getDefaultInstance(clazz)
        ProtobufNamedTypeInfoProvider.newStructInfo(message.companion.javaDescriptor).withAlias(clazz.getName())
      case _ => null
    }
  }
}
