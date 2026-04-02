package dk.viplev.agent.config;

import dk.viplev.agent.generated.model.HostDTO;
import dk.viplev.agent.port.outbound.host.HostInfoProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Component
public class HostInfoProviderImpl implements HostInfoProvider {

    private static final Logger log = LoggerFactory.getLogger(HostInfoProviderImpl.class);

    private final String machineIdPath;
    private final String procPath;

    private HostDTO cachedHostInfo;

    public HostInfoProviderImpl(
            @Value("${agent.machine-id-path:/etc/machine-id}") String machineIdPath,
            @Value("${agent.proc-path:/proc}") String procPath) {
        this.machineIdPath = machineIdPath;
        this.procPath = procPath;
    }

    @PostConstruct
    void init() {
        this.cachedHostInfo = buildHostInfo();
    }

    @Override
    public HostDTO getHostInfo() {
        return cachedHostInfo;
    }

    private HostDTO buildHostInfo() {
        return new HostDTO()
                .name(readHostname())
                .machineId(readMachineId())
                .ipAddress(readIpAddress())
                .os(System.getProperty("os.name") != null ? System.getProperty("os.name") : "unknown")
                .osVersion(System.getProperty("os.version"))
                .cpuModel(readCpuModel())
                .cpuCores(readCpuCores())
                .cpuThreads(Runtime.getRuntime().availableProcessors())
                .ramTotalBytes(readRamTotalBytes());
    }

    private String readHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("Failed to read hostname, using 'unknown'", e);
            return "unknown";
        }
    }

    private String readMachineId() {
        try {
            return Files.readString(Path.of(machineIdPath)).trim();
        } catch (Exception e) {
            log.warn("Failed to read machine-id from {}, using empty string", machineIdPath, e);
            return "";
        }
    }

    private String readIpAddress() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return InetAddress.getLocalHost().getHostAddress();
            }
            for (var iface : Collections.list(interfaces)) {
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                for (var addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Failed to determine IP address, using '0.0.0.0'", e);
            return "0.0.0.0";
        }
    }

    private String readCpuModel() {
        try {
            String cpuinfo = Files.readString(Path.of(procPath, "cpuinfo"));
            for (String line : cpuinfo.split("\n")) {
                if (line.startsWith("model name")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        return line.substring(colon + 1).trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read CPU model from {}/cpuinfo", procPath, e);
        }
        return null;
    }

    private Integer readCpuCores() {
        try {
            String cpuinfo = Files.readString(Path.of(procPath, "cpuinfo"));
            Set<String> coreIds = new HashSet<>();
            String currentPhysicalId = "0";
            for (String line : cpuinfo.split("\n")) {
                if (line.startsWith("physical id")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        currentPhysicalId = line.substring(colon + 1).trim();
                    }
                } else if (line.startsWith("core id")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        coreIds.add(currentPhysicalId + ":" + line.substring(colon + 1).trim());
                    }
                }
            }
            if (!coreIds.isEmpty()) {
                return coreIds.size();
            }
        } catch (Exception e) {
            log.warn("Failed to read CPU cores from {}/cpuinfo", procPath, e);
        }
        return Runtime.getRuntime().availableProcessors();
    }

    private Long readRamTotalBytes() {
        try {
            String meminfo = Files.readString(Path.of(procPath, "meminfo"));
            for (String line : meminfo.split("\n")) {
                if (line.startsWith("MemTotal:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) * 1024;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read RAM total from {}/meminfo", procPath, e);
        }
        return null;
    }
}
