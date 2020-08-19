package example.armeria.server.annotated.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AnnotatedServiceTest {
    companion object {
        private lateinit var server: Server
        private lateinit var client: WebClient

        @BeforeAll
        @JvmStatic
        fun beforeClass() {
            server = newServer(0)
            server.start().join()
            client = WebClient.of("http://127.0.0.1:" + server.activeLocalPort())
        }

        @AfterAll
        @JvmStatic
        fun afterClass() {
            server.stop().join()
            client.options().factory().close()
        }
    }

    @Test
    fun testContextAwareService() {
        val res = client.get("/contextAware/foo?name=armeria&id=100").aggregate().join()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThatJson(res.contentUtf8())
            .node("id").isEqualTo(100)
            .node("name").isEqualTo("armeria")
    }

    @Test
    fun testDecoratingService() {
        client.get("/decorating/foo").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.OK)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }

        client.get("/decorating/bar").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.OK)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }

        client.get("/decorating/blocking").aggregate().join().let {
            assertThat(it.status()).isEqualTo(HttpStatus.OK)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }
    }
}
