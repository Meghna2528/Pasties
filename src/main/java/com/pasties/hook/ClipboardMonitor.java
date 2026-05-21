package com.pasties.hook;

import com.pasties.service.ClipboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the AWT system clipboard at a fixed interval for new text content.
 *
 * <h2>Why polling instead of FlavorListener?</h2>
 * <p>macOS's AWT {@link java.awt.datatransfer.FlavorListener} implementation is
 * unreliable across JDK versions — it frequently misses changes made by other
 * applications. A 500 ms poll is imperceptible to the user and guarantees that
 * every clipboard change is detected.
 *
 * <h2>Re-entry prevention</h2>
 * <p>Before recording a new clipboard entry the monitor checks the
 * {@code isProgrammaticPaste} flag supplied by {@link com.pasties.service.PasteService}.
 * When the flag is set it means Pasties itself wrote to the clipboard (for a
 * paste or snippet expansion), and the event is skipped to prevent
 * self-contamination of the history.
 *
 * <p>All polling runs on the {@code clipboard-monitor} daemon thread, never
 * on the EDT.
 */
public class ClipboardMonitor {

    private static final Logger log = LoggerFactory.getLogger(ClipboardMonitor.class);
    private static final long POLL_INTERVAL_MS = 500;

    private final ClipboardService clipboardService;
    private final AtomicBoolean isProgrammaticPaste;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clipboard-monitor");
        t.setDaemon(true);
        return t;
    });

    /** Last seen clipboard text; used to detect changes without hashing. */
    private String lastContent = null;

    private ScheduledFuture<?> pollTask;

    public ClipboardMonitor(ClipboardService clipboardService, AtomicBoolean isProgrammaticPaste) {
        this.clipboardService = clipboardService;
        this.isProgrammaticPaste = isProgrammaticPaste;
    }

    /**
     * Starts the polling loop. Safe to call only once.
     */
    public void start() {
        pollTask = scheduler.scheduleAtFixedRate(
                this::checkClipboard, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Clipboard monitor started ({}ms interval)", POLL_INTERVAL_MS);
    }

    private void checkClipboard() {
        // Skip poll while PasteService is writing to the clipboard
        if (isProgrammaticPaste.get()) {
            return;
        }

        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return; // Non-text clipboard content (image, file, etc.) — ignore
            }

            String content = (String) clipboard.getData(DataFlavor.stringFlavor);
            if (content == null || content.equals(lastContent)) {
                return; // No change
            }

            lastContent = content;
            log.debug("New clipboard content detected ({} chars)", content.length());
            clipboardService.onNewContent(content);

        } catch (UnsupportedFlavorException e) {
            // Clipboard has content but not as text — benign, skip
        } catch (IllegalStateException e) {
            // Clipboard temporarily unavailable (another app is mid-write) — retry next cycle
            log.trace("Clipboard busy, skipping poll cycle");
        } catch (Exception e) {
            log.error("Unexpected error during clipboard poll", e);
        }
    }

    /**
     * Stops the polling loop and shuts down the scheduler.
     * Blocks briefly to allow the current poll cycle to complete.
     */
    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Clipboard monitor stopped");
    }
}
