package io.github.voraes.jscheduler.spring;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External configuration for the auto-configured scheduler. */
@ConfigurationProperties("j-scheduler")
public class JSchedulerProperties {
    private final Execution execution = new Execution();
    private final Shutdown shutdown = new Shutdown();
    private boolean shutdownHook;

    public Execution getExecution() {
        return execution;
    }

    public Shutdown getShutdown() {
        return shutdown;
    }

    /** Whether to register an additional JVM shutdown hook. Spring shutdown remains enabled. */
    public boolean isShutdownHook() {
        return shutdownHook;
    }

    public void setShutdownHook(boolean shutdownHook) {
        this.shutdownHook = shutdownHook;
    }

    public static class Execution {
        private Mode mode = Mode.PLATFORM;
        private int platformThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private int maxConcurrentJobs = 100;

        /** Execution thread strategy. */
        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
        }

        /** Number of bounded workers in platform mode. */
        public int getPlatformThreads() {
            return platformThreads;
        }

        public void setPlatformThreads(int platformThreads) {
            if (platformThreads < 1) {
                throw new IllegalArgumentException("platformThreads must be positive");
            }
            this.platformThreads = platformThreads;
        }

        /** Maximum simultaneous tasks in virtual-thread mode. */
        public int getMaxConcurrentJobs() {
            return maxConcurrentJobs;
        }

        public void setMaxConcurrentJobs(int maxConcurrentJobs) {
            if (maxConcurrentJobs < 1) {
                throw new IllegalArgumentException("maxConcurrentJobs must be positive");
            }
            this.maxConcurrentJobs = maxConcurrentJobs;
        }
    }

    public static class Shutdown {
        private Duration timeout = Duration.ofSeconds(30);

        /** Grace period before running jobs receive interruption requests. */
        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative()) {
                throw new IllegalArgumentException("shutdown timeout must not be negative");
            }
            this.timeout = timeout;
        }
    }

    public enum Mode {
        PLATFORM,
        VIRTUAL
    }
}
