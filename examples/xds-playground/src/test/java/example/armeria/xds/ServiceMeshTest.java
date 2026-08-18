package example.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ServiceMeshTest {

    private static ServiceMesh mesh;

    @BeforeAll
    static void setUp() {
        mesh = new ServiceMesh("basic");
    }

    @AfterAll
    static void tearDown() {
        if (mesh != null) {
            mesh.close();
        }
    }

    @Test
    void basicProfile() {
        final AggregatedHttpResponse response = mesh.client().blocking().get("/");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }
}
