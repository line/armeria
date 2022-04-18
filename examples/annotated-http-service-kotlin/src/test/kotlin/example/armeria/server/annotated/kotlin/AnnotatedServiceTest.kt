package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class AnnotatedServiceTest {

    companion object {

        @JvmField
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                configureServices(sb)
            }
        }

        fun client(): WebClient {
            return WebClient.of(server.httpUri())
        }
    }

    @Test
    fun testContextAwareService() {
        val res = client().prepare().get("/contextAware/foo?name=armeria&id=100").asString().execute().join()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThatJson(res.content())
            .node("id").isEqualTo(100)
            .node("name").isEqualTo("armeria")
    }

    @Test
    fun testDecoratingService() {
        val client = client()
        client.prepare()
            .get("/decorating/foo")
            .asString()
            .execute()
            .join().let {
                assertThat(it.status()).isEqualTo(HttpStatus.OK)
                assertThat(it.content()).isEqualTo("OK")
            }

        client.prepare()
            .get("/decorating/bar")
            .asString()
            .execute()
            .join().let {
                assertThat(it.status()).isEqualTo(HttpStatus.OK)
                assertThat(it.content()).isEqualTo("OK")
            }

        client.prepare()
            .get("/decorating/blocking")
            .asString()
            .execute()
            .join().let {
                assertThat(it.status()).isEqualTo(HttpStatus.OK)
                assertThat(it.content()).isEqualTo("OK")
            }
    }
}
