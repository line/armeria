package example.armeria.contextpropagation.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server

fun main() {
    val backend = Server.builder()
        .service("/square/{num}") { ctx, _ ->
            val num = ctx.pathParam("num")?.toLong()
            if (num != null) {
                HttpResponse.of((num * num).toString())
            } else {
                HttpResponse.of(HttpStatus.BAD_REQUEST)
            }
        }
        .http(8081)
        .build()

    val backendClient = WebClient.of("http://localhost:8081")

    val frontend = Server.builder()
        .http(8080)
        .serviceUnder("/", MainService(backendClient))
        .build()

    Runtime.getRuntime().addShutdownHook(Thread {
        backend.stop().join()
        frontend.stop().join()
    })

    backend.start().join()
    frontend.start().join()
}
