package dk.viplev.agent.adapter.outbound.container.docker;

import dk.viplev.agent.domain.exception.ContainerRuntimeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProcFileReader {

    private final Path procBase;

    public ProcFileReader(Path procBase) {
        this.procBase = procBase;
    }

    String readStat() {
        return readFile("stat");
    }

    String readMeminfo() {
        return readFile("meminfo");
    }

    String readNetDev() {
        return readFile("net/dev");
    }

    String readDiskstats() {
        return readFile("diskstats");
    }

    private String readFile(String relativePath) {
        try {
            return Files.readString(procBase.resolve(relativePath));
        } catch (IOException e) {
            throw new ContainerRuntimeException(
                    "Failed to read " + procBase.resolve(relativePath) + ": " + e.getMessage(), e);
        }
    }
}
