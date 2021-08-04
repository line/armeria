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

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.sangria.GraphqlTestUtil.executeQuery
import com.linecorp.armeria.server.{ServerBuilder, ServiceRequestContext}
import munit.FunSuite
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import sangria.macros.derive._
import sangria.schema._
import scala.concurrent.Future

class GraphqlExecutionContextSuite extends FunSuite with ServerSuite {

  import Products._

  override protected def configureServer: ServerBuilder => Unit = {
    _.service("/graphql-eventloop", SangriaGraphqlService(ProductSchema, new ProductRepo(isBlocking = false)))
      .service(
        "/graphql-blocking",
        SangriaGraphqlService
          .builder(ProductSchema, new ProductRepo(isBlocking = true))
          .useBlockingTaskExecutor(true)
          .build())
  }

  for {
    method <- List(HttpMethod.GET, HttpMethod.POST)
    path <- List("/graphql-eventloop", "/graphql-blocking")
  } test(s"a graphql resolver should be executed in $method $path") {
    val query1 =
      """
      query GetProductById {
        product(id: "1") {
          id
          name
        }
      }
      """

    val response1 = executeQuery(client, method = method, path = path, query = query1)
    println(response1.contentUtf8())
    assertThatJson(response1.contentUtf8()).isEqualTo("""
          {
            "data": {
              "product": {
                "id": "1",
                "name":"Armeria"
               }
             }
           }
          """)

    val query2 =
      """
      query GetProductById {
        products {
          id
          name
        }
      }
      """

    val response2 = executeQuery(client, method = method, path = path, query = query2)
    assertThatJson(response2.contentUtf8()).isEqualTo("""
        {
          "data": {
            "products": [
              {
                "id": "1",
                "name":"Armeria"
              },
              {
                "id": "2",
                "name":"Health Potion"
              }
            ]
          }
        }
        """)
  }

  object Products {
    case class Product(id: String, name: String, description: String)

    val ProductType: ObjectType[Unit, Product] = deriveObjectType[Unit, Product]()

    class ProductRepo(isBlocking: Boolean) {
      private val Products: List[Product] =
        List(Product("1", "Armeria", "Tasty"), Product("2", "Health Potion", "+50 HP"))

      def product(id: String): Option[Product] = {
        if (isBlocking) {
          // Should be executed by a blocking task executor
          assert(!ServiceRequestContext.current().eventLoop().inEventLoop())
        } else {
          assert(ServiceRequestContext.current().eventLoop().inEventLoop())
        }
        Products.find(_.id == id)
      }

      def products(): Future[List[Product]] = {
        if (isBlocking) {
          // Should be executed by a blocking task executor
          assert(!ServiceRequestContext.current().eventLoop().inEventLoop())
        } else {
          assert(ServiceRequestContext.current().eventLoop().inEventLoop())
        }
        Future.successful(Products)
      }
    }

    val Id: Argument[String] = Argument("id", StringType)

    val QueryType: ObjectType[ProductRepo, Unit] = ObjectType(
      "Query",
      fields[ProductRepo, Unit](
        Field(
          "product",
          OptionType(ProductType),
          description = Some("Returns a product with specific `id`."),
          arguments = Id :: Nil,
          resolve = c => c.ctx.product(c.arg(Id))),
        Field(
          "products",
          ListType(ProductType),
          description = Some("Returns a list of all available products."),
          resolve = _.ctx.products())
      )
    )

    val ProductSchema: Schema[ProductRepo, Unit] = Schema(QueryType)
  }
}
