package dk.viplev.agent.adapter.outbound.container.docker;

import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcFileReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readStat_returnsFileContent() throws IOException {
        String content = "cpu  100 0 50 800 10 0 0 0 0 0\ncpu0 50 0 25 400 5 0 0 0 0 0\n";
        Files.writeString(tempDir.resolve("stat"), content);

        var reader = new ProcFileReader(tempDir);

        assertThat(reader.readStat()).isEqualTo(content);
    }

    @Test
    void readMeminfo_returnsFileContent() throws IOException {
        String content = "MemTotal:       16384000 kB\nMemAvailable:    8192000 kB\n";
        Files.writeString(tempDir.resolve("meminfo"), content);

        var reader = new ProcFileReader(tempDir);

        assertThat(reader.readMeminfo()).isEqualTo(content);
    }

    @Test
    void readNetDev_returnsFileContent() throws IOException {
        Path netDir = tempDir.resolve("net");
        Files.createDirectories(netDir);
        String content = "Inter-|   Receive\n face |bytes\n  eth0: 1000 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0\n";
        Files.writeString(netDir.resolve("dev"), content);

        var reader = new ProcFileReader(tempDir);

        assertThat(reader.readNetDev()).isEqualTo(content);
    }

    @Test
    void readDiskstats_returnsFileContent() throws IOException {
        String content = "   8       0 sda 100 0 200 0 50 0 100 0 0 0 0 0 0 0 0 0 0\n";
        Files.writeString(tempDir.resolve("diskstats"), content);

        var reader = new ProcFileReader(tempDir);

        assertThat(reader.readDiskstats()).isEqualTo(content);
    }

    @Test
    void readStat_throwsContainerRuntimeExceptionOnMissingFile() {
        var reader = new ProcFileReader(tempDir);

        assertThatThrownBy(reader::readStat)
                .isInstanceOf(ContainerRuntimeException.class)
                .hasMessageContaining("Failed to read")
                .hasMessageContaining("stat");
    }
}
