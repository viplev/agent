package dk.viplev.agent.domain.exception;

public class ContainerRuntimeException extends AgentException {

    public ContainerRuntimeException(String message) {
        super(message);
    }

    public ContainerRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
