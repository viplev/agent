package dk.viplev.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HostInfoProviderImplTest {

    @TempDir
    Path tempDir;

    @Test
    void getHostInfo_readsMachineId() throws IOException {
        Path machineIdFile = tempDir.resolve("machine-id");
        Files.writeString(machineIdFile, "abc123def456\n");

        var provider = createProvider(machineIdFile.toString());

        assertThat(provider.getHostInfo().getMachineId()).isEqualTo("abc123def456");
    }

    @Test
    void getHostInfo_parsesMemTotal() throws IOException {
        writeProcFile("meminfo", """
                MemTotal:       16384000 kB
                MemFree:         8192000 kB
                MemAvailable:   12288000 kB
                """);

        var provider = createProvider(tempDir.resolve("machine-id").toString());
        Files.writeString(tempDir.resolve("machine-id"), "test");

        assertThat(provider.getHostInfo().getRamTotalBytes()).isEqualTo(16384000L * 1024);
    }

    @Test
    void getHostInfo_parsesCpuModel() throws IOException {
        writeProcFile("cpuinfo", """
                processor\t: 0
                model name\t: Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz
                core id\t\t: 0
                physical id\t: 0

                processor\t: 1
                model name\t: Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz
                core id\t\t: 1
                physical id\t: 0
                """);
        Files.writeString(tempDir.resolve("machine-id"), "test");

        var provider = createProvider(tempDir.resolve("machine-id").toString());

        assertThat(provider.getHostInfo().getCpuModel())
                .isEqualTo("Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz");
    }

    @Test
    void getHostInfo_countsCpuCores() throws IOException {
        writeProcFile("cpuinfo", """
                processor\t: 0
                physical id\t: 0
                core id\t\t: 0

                processor\t: 1
                physical id\t: 0
                core id\t\t: 1

                processor\t: 2
                physical id\t: 0
                core id\t\t: 0

                processor\t: 3
                physical id\t: 0
                core id\t\t: 1
                """);
        Files.writeString(tempDir.resolve("machine-id"), "test");

        var provider = createProvider(tempDir.resolve("machine-id").toString());

        assertThat(provider.getHostInfo().getCpuCores()).isEqualTo(2);
    }

    @Test
    void getHostInfo_returnsRequiredFieldsWithMissingProcFiles() throws IOException {
        Files.writeString(tempDir.resolve("machine-id"), "fallback-id");

        // procPath points to tempDir which has no cpuinfo/meminfo
        var provider = createProvider(tempDir.resolve("machine-id").toString());

        var host = provider.getHostInfo();
        assertThat(host.getName()).isNotBlank();
        assertThat(host.getMachineId()).isEqualTo("fallback-id");
        assertThat(host.getIpAddress()).isNotBlank();
        assertThat(host.getOs()).isNotBlank();
        assertThat(host.getCpuThreads()).isPositive();
    }

    @Test
    void getHostInfo_handlesMissingMachineIdFile() {
        var provider = createProvider(tempDir.resolve("nonexistent").toString());

        assertThat(provider.getHostInfo().getMachineId())
                .isNotBlank()
                .matches("[0-9a-f]{32}");
    }

    @Test
    void getHostInfo_fallbackMachineId_isDeterministic() {
        var provider1 = createProvider(tempDir.resolve("nonexistent").toString());
        var provider2 = createProvider(tempDir.resolve("nonexistent").toString());

        assertThat(provider1.getHostInfo().getMachineId())
                .isEqualTo(provider2.getHostInfo().getMachineId());
    }

    @Test
    void getHostInfo_usesMachineIdOverride() throws IOException {
        Path machineIdFile = tempDir.resolve("machine-id");
        Files.writeString(machineIdFile, "file-value");

        var provider = createProvider("my-custom-id", machineIdFile.toString());

        assertThat(provider.getHostInfo().getMachineId()).isEqualTo("my-custom-id");
    }

    private HostInfoProviderImpl createProvider(String machineIdPath) {
        return createProvider("", machineIdPath);
    }

    private HostInfoProviderImpl createProvider(String machineIdOverride, String machineIdPath) {
        var provider = new HostInfoProviderImpl(machineIdOverride, machineIdPath, tempDir.toString());
        provider.init();
        return provider;
    }

    private void writeProcFile(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }
}
