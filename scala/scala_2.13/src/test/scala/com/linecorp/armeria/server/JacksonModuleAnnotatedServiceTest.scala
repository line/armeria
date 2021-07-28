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

package com.linecorp.armeria.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.{HttpStatus, MediaType}
import com.linecorp.armeria.server.annotation.{Post, ProducesJson}
import munit.FunSuite
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson

class JacksonModuleAnnotatedServiceTest extends FunSuite with ServerSuite {

  override protected def configureServer: ServerBuilder => Unit = {
    _.annotatedService(new ServiceWithCaseClass, Array.emptyObjectArray: _*)
  }

  List("/echo", "/echo-option").foreach { path =>
    test(s"$path - should encode and decode case class from and to JSON") {
      val client = WebClient.of(server.httpUri())
      val json = """{"x":10,"y":"hello"}"""
      val response = client
        .prepare()
        .post(path)
        .content(MediaType.JSON, json)
        .execute()
        .aggregate()
        .join()
      assertEquals(response.contentUtf8(), json)
    }
  }

  List("/echo", "/echo-option").foreach { path =>
    test(s"$path - should return 404 Bad Request for null values with non-Option value") {
      val client = WebClient.of(server.httpUri())
      val json = """{"x":10}"""
      val response = client
        .prepare()
        .post(path)
        .content(MediaType.JSON, json)
        .execute()
        .aggregate()
        .join()
      if (path == "/echo") {
        // should fail to decode null value to a String
        assertEquals(response.status(), HttpStatus.BAD_REQUEST)
      } else {
        assertThatJson(response.contentUtf8()).isEqualTo("""
          {
            "x" : 10,
            "y" : null
          }
          """)
      }
    }
  }
}

class ServiceWithCaseClass {

  @ProducesJson
  @Post("/echo")
  def echo(foo: Foo): Foo = {
    foo
  }

  @ProducesJson
  @Post("/echo-option")
  def echo(foo: FooWithOption): FooWithOption = {
    foo
  }
}

case class Foo(x: Int, y: String)

case class FooWithOption(x: Int, y: Option[String])
