package dk.viplev.agent.domain.model;

import java.util.List;
import java.util.Map;

public record ContainerStartRequest(
        String imageName,
        Map<String, String> env,
        Map<String, String> volumes,
        String network,
        List<String> command
) {}
