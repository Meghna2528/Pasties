package com.pasties.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Central shutdown coordinator.
 *
 * <p>Components register a {@link Runnable} shutdown hook via
 * {@link #registerShutdownHook(Runnable)}. When {@link #shutdown()} is called
 * (either programmatically from "Quit" or via the JVM shutdown hook), all
 * registered hooks are invoked in <em>reverse registration order</em> (LIFO),
 * mirroring standard resource teardown semantics.
 *
 * <p>A JVM shutdown hook is installed automatically in the static initializer
 * so that {@code Ctrl+C} and OS-level termination signals are handled cleanly.
 */
public final class AppLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AppLifecycle.class);

    private static final Deque<Runnable> hooks = new ArrayDeque<>();
    private static volatile boolean shutdownInProgress = false;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("JVM shutdown hook triggered");
            shutdown();
        }, "pasties-shutdown"));
    }

    private AppLifecycle() {}

    /**
     * Registers a shutdown hook. Hooks are called in reverse order of registration.
     *
     * @param hook cleanup runnable (must not throw unchecked exceptions)
     */
    public static synchronized void registerShutdownHook(Runnable hook) {
        hooks.push(hook);
    }

    /**
     * Invokes all registered hooks in LIFO order. Safe to call multiple times;
     * subsequent calls are no-ops once shutdown is in progress.
     */
    public static synchronized void shutdown() {
        if (shutdownInProgress) {
            return;
        }
        shutdownInProgress = true;
        log.info("Shutting down — {} hook(s) to run", hooks.size());

        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (Exception e) {
                log.error("Error in shutdown hook", e);
            }
        }
        hooks.clear();
        log.info("Shutdown complete");
    }
}
