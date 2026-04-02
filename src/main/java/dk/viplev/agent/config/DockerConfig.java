package dk.viplev.agent.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import dk.viplev.agent.adapter.outbound.container.docker.ProcFileReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

@Configuration
@Profile("docker")
public class DockerConfig {

    @Bean
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    public ProcFileReader procFileReader(@Value("${agent.proc-path:/proc}") String procPath) {
        if (procPath == null || procPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Configuration property 'agent.proc-path' must be set to a non-empty path.");
        }
        return new ProcFileReader(Path.of(procPath));
    }
}
