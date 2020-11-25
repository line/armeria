package example.springframework.boot.minimal.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HelloApplicationIntegrationTest(@Autowired server: Server) {

    private val client: WebClient = WebClient.of("http://localhost:" + server.activeLocalPort())

    @Test
    fun success() {
        val response = client.get("/hello/Spring").aggregate().join()
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        assertThat(response.contentUtf8())
            .isEqualTo("Hello, Spring! This message is from Armeria annotated service!")
    }

    @Test
    fun failure() {
        val response = client.get("/hello/a").aggregate().join()
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThatJson(response.contentUtf8()).node("message")
            .isEqualTo("hello.name: name should have between 3 and 10 characters")
    }
}
