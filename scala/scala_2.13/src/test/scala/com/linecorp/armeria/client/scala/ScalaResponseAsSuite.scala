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

import _root_.scala.concurrent.ExecutionContext.Implicits.global
import _root_.scala.concurrent.duration.Duration
import _root_.scala.concurrent.{Await, Future}
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.scala.ScalaResponseAs.{bytes, file, json, string}
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.scala.implicits._
import com.linecorp.armeria.server.{ServerBuilder, ServerSuite}
import java.io.File
import java.nio.file.Files
import munit.FunSuite

class ScalaResponseAsSuite extends FunSuite with ServerSuite {
  override protected def configureServer: ServerBuilder => Unit = { sb =>
    sb.service("/json", (_, _) => HttpResponse.ofJson(TestValueHolder("hello")))
    sb.service("/string", (_, _) => HttpResponse.of("hello"))
  }

  test("should be able to convert a JSON response into a case class") {
    val client = WebClient.of(server.httpUri())
    val response: Future[TestValueHolder] =
      client
        .prepare()
        .get("/json")
        .as(json[TestValueHolder])
        .execute()
        .map(_.content())

    assertEquals(Await.result(response, Duration.Inf), TestValueHolder("hello"))
  }

  test("should be able to convert a response into a string") {
    val client = WebClient.of(server.httpUri())
    val response: Future[String] =
      client
        .prepare()
        .get("/string")
        .as(string())
        .execute()
        .map(_.content())

    assertEquals(Await.result(response, Duration.Inf), "hello")
  }

  test("should be able to convert a response into bytes") {
    val client = WebClient.of(server.httpUri())
    val response: Future[Array[Byte]] =
      client
        .prepare()
        .get("/string")
        .as(bytes())
        .execute()
        .map(_.content())

    assert(Await.result(response, Duration.Inf).sameElements("hello".getBytes))
  }

  test("should be able to write a response into a file") {
    val client = WebClient.of(server.httpUri())
    val response: Future[Array[Byte]] =
      client
        .prepare()
        .get("/string")
        .as(bytes())
        .execute()
        .map(_.content())

    assert(Await.result(response, Duration.Inf).sameElements("hello".getBytes))
  }

  test("should be able to write a response into a file") {
    val tempFile = Files.createTempDirectory("test").resolve("foo.txt").toFile

    try {
      val client = WebClient.of(server.httpUri())
      val response: Future[File] =
        client
          .prepare()
          .get("/string")
          .as(file(tempFile))
          .execute()
          .map(_.content())

      val file1 = Await.result(response, Duration.Inf)
      assertEquals(Files.readAllBytes(file1.toPath).toSeq, "hello".getBytes.toSeq)
    } finally {
      tempFile.delete()
    }
  }
}

case class TestValueHolder(id: String)
