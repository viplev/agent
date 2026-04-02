package dk.viplev.agent.port.outbound.host;

import dk.viplev.agent.generated.model.HostDTO;

public interface HostInfoProvider {

    HostDTO getHostInfo();
}
