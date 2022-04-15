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

package com.linecorp.armeria.server.scalapb

import com.linecorp.armeria.common.annotation.{Nullable, UnstableApi}
import com.linecorp.armeria.server.annotation.{ResponseConverterFunction, ResponseConverterFunctionProvider}
import com.linecorp.armeria.server.scalapb.ScalaPbConverterUtil.{isSupportedGenericType, toResultType}
import java.lang.reflect.Type

/**
 * Provides a [[com.linecorp.armeria.server.scalapb.ScalaPbResponseConverterFunction]] to annotated services.
 */
@UnstableApi
final class ScalaPbResponseConverterFunctionProvider extends ResponseConverterFunctionProvider {

  @Nullable
  override def createResponseConverterFunction(
      returnType: Type,
      responseConverter: ResponseConverterFunction): ResponseConverterFunction =
    if (toResultType(returnType) != ResultType.UNKNOWN || isSupportedGenericType(returnType))
      new ScalaPbResponseConverterFunction()
    else
      null
}
