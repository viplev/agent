package dk.viplev.agent.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionTest {

    @Test
    void agentExceptionWithMessage() {
        var ex = new AgentException("something failed");
        assertThat(ex.getMessage()).isEqualTo("something failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void agentExceptionWithMessageAndCause() {
        var cause = new RuntimeException("root cause");
        var ex = new AgentException("something failed", cause);
        assertThat(ex.getMessage()).isEqualTo("something failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void viplevApiExceptionPreservesStatusCode() {
        var ex = new ViplevApiException("not found", 404);
        assertThat(ex.getMessage()).isEqualTo("not found");
        assertThat(ex).hasFieldOrPropertyWithValue("statusCode", 404);
        assertThat(ex).isInstanceOf(AgentException.class);
    }

    @Test
    void viplevApiExceptionWithCause() {
        var cause = new RuntimeException("connection refused");
        var ex = new ViplevApiException("api error", 503, cause);
        assertThat(ex).hasFieldOrPropertyWithValue("statusCode", 503);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void containerRuntimeExceptionWithMessage() {
        var ex = new ContainerRuntimeException("docker not available");
        assertThat(ex.getMessage()).isEqualTo("docker not available");
        assertThat(ex).isInstanceOf(AgentException.class);
    }

    @Test
    void containerRuntimeExceptionWithMessageAndCause() {
        var cause = new RuntimeException("socket error");
        var ex = new ContainerRuntimeException("docker failed", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
