package dk.viplev.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerConfigTest {

    private final DockerConfig config = new DockerConfig();

    @Test
    void dockerClient_isCreatedSuccessfully() {
        var client = config.dockerClient();

        assertThat(client).isNotNull();
    }

    @Test
    void procFileReader_isCreatedWithPath() {
        var reader = config.procFileReader("/custom/proc");

        assertThat(reader).isNotNull();
    }
}
