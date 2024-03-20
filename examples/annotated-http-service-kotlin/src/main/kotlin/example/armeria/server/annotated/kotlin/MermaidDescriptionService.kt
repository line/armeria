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
package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.annotation.Description
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.docs.Markup

@CoroutineNameDecorator(name = "default")
class MermaidDescriptionService {
    @Description(
        value = """
        gantt
            title A Gantt Diagram
            dateFormat  YYYY-MM-DD
            section Section
            A task           :a1, 2014-01-01, 30d
            Another task     :after a1  , 20d
            section Another
            Task in sec      :2014-01-12  , 12d
            another task      : 24d
        """,
        markup = Markup.MERMAID,
    )
    @Get("/mermaid")
    fun mermaid(
        @Param param1: String,
    ): HttpResponse {
        return HttpResponse.of(200)
    }
}
