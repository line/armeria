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
package com.linecorp.armeria.common.util

import java.util.concurrent.{CompletableFuture, CompletionStage}

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.scala.directExecutionContext
import com.linecorp.armeria.scala.implicits._
import munit.FunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class FutureSuite extends FunSuite {
  implicit val ec: ExecutionContext = directExecutionContext

  test("CompletionStage[Void].toScala should return Future[Unit].") {
    val javaFuture = new CompletableFuture[Void]()
    val scalaFuture: Future[Unit] = javaFuture.toScala

    javaFuture.complete(null)
    assert(Await.result(scalaFuture, 10.seconds).isInstanceOf[Unit])
  }

  test("CompletionStage[HttpResponse].toHttpResponse") {
    val javaFuture = new CompletableFuture[HttpResponse]()
    assert(javaFuture.toHttpResponse.isInstanceOf[HttpResponse])
  }

  test("Future[Unit].toJava should return CompletionStage[Void].") {
    val scalaFuture: Future[Unit] = Future {
      ()
    }
    val javaFuture: CompletionStage[Void] = scalaFuture.toJava

    assert(javaFuture.toCompletableFuture.join() == null)
  }

  test("Future[HttpResponse].toHttpResponse") {
    val scalaFuture = Future {
      HttpResponse.of(200)
    }
    assert(scalaFuture.toHttpResponse.isInstanceOf[HttpResponse])
  }
}
