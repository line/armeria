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

import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.sangria.GraphqlTestUtil.executeQuery
import munit.FunSuite
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import sangria.execution.deferred.DeferredResolver

class SangriaGraphqlSuite extends FunSuite with ServerSuite {

  override protected def configureServer: ServerBuilder => Unit = { server =>
    server.service(
      "/graphql",
      SangriaGraphqlService
        .builder(SchemaDefinition.StarWarsSchema, new CharacterRepo)
        .deferredResolver(DeferredResolver.fetchers(SchemaDefinition.characters))
        .build()
    )
  }

  // Forked from https://github.com/sangria-graphql/sangria-playground/blob/24e36833bd3b784db57dc57cf5523c504e97f8ff/test/SchemaSpec.scala

  test("correctly identify R2-D2 as the hero of the Star Wars Saga") {
    val query =
      """
        query HeroNameQuery {
          hero {
            name
          }
        }
        """
    val response = executeQuery(client, query = query)

    assertEquals(response.headers().status(), HttpStatus.OK)
    assertThatJson(response.contentUtf8()).isEqualTo("""
         {
           "data": {
             "hero": {
               "name": "R2-D2"
             }
           }
         }
        """)
  }

  test("allow to fetch Han Solo using his ID provided through variables") {
    val query =
      """
         query FetchSomeIDQuery($humanId: String!) {
           human(id: $humanId) {
             name
             friends {
               id
               name
             }
           }
         }
       """

    val response = executeQuery(client, query = query, variables = Map("humanId" -> "1002"))
    assertEquals(response.headers().status(), HttpStatus.OK)

    assertThatJson(response.contentUtf8())
      .isEqualTo("""
         {
           "data": {
             "human": {
               "name": "Han Solo",
               "friends": [
                 {
                   "id": "1000",
                   "name": "Luke Skywalker"
                 },
                 {
                   "id": "1003",
                   "name": "Leia Organa"
                 },
                 {
                   "id": "2001",
                   "name": "R2-D2"
                 }
               ]
             }
           }
         }
        """)
  }
}
