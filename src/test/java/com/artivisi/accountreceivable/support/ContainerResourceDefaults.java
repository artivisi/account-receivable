package com.artivisi.accountreceivable.support;

import com.github.dockerjava.api.command.CreateContainerCmd;
import org.testcontainers.core.CreateContainerCmdModifier;

/**
 * Gives every Testcontainers container an explicit memory and CPU limit.
 *
 * <p>Docker Engine and OrbStack run all containers inside one VM and hand out memory on demand, so
 * an unset limit costs nothing. Apple Container gives each container its own VM with a <em>fixed</em>
 * reservation, and socktainer sizes anything created without an explicit limit at 1 GiB / 4 CPUs.
 * Testcontainers never sets one, so container count multiplies real RAM rather than sharing it.
 *
 * <p>Registered through {@code META-INF/services}, so it applies to every container — including the
 * {@code exposeHostPorts} sshd helper, which no test constructs directly. Harmless on Docker Engine,
 * where it only sets a bound the fixtures already live within. Override with
 * {@code TC_CONTAINER_MEMORY_MB}.
 *
 * <p>Delete this class and its service registration once socktainer honours Apple Container's own
 * {@code [container].memory} default.
 */
public class ContainerResourceDefaults implements CreateContainerCmdModifier {

    /** Postgres test fixtures are comfortable here. */
    private static final long DEFAULT_MEGABYTES = 512L;

    /** Ryuk and the sshd port-forwarder are tiny Go/BusyBox images. */
    private static final long HELPER_MEGABYTES = 256L;

    private static final long NANO_CPUS_PER_CPU = 1_000_000_000L;

    @Override
    public CreateContainerCmd modify(CreateContainerCmd cmd) {
        String image = cmd.getImage() == null ? "" : cmd.getImage();
        cmd.getHostConfig()
                .withMemory(megabytesFor(image) * 1024L * 1024L)
                .withNanoCPUs(cpusFor(image) * NANO_CPUS_PER_CPU);
        return cmd;
    }

    private static long megabytesFor(String image) {
        if (isHelper(image)) {
            return HELPER_MEGABYTES;
        }
        String configured = System.getenv("TC_CONTAINER_MEMORY_MB");
        return (configured == null || configured.isBlank())
                ? DEFAULT_MEGABYTES
                : Long.parseLong(configured.trim());
    }

    private static long cpusFor(String image) {
        return isHelper(image) ? 1L : 2L;
    }

    private static boolean isHelper(String image) {
        return image.contains("testcontainers/ryuk") || image.contains("testcontainers/sshd");
    }
}
