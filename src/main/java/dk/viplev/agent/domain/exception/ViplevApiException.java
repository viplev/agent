package dk.viplev.agent.domain.exception;

import lombok.Getter;

@Getter
public class ViplevApiException extends AgentException {

    private final int statusCode;

    public ViplevApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ViplevApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
