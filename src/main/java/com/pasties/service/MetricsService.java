package com.pasties.service;

import com.pasties.domain.ClipboardEntry;
import com.pasties.domain.Snippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and exposes runtime performance metrics for Pasties.
 *
 * <h2>Metrics exposed</h2>
 * <ul>
 *   <li><b>Paste speed</b> — average elapsed milliseconds per paste operation
 *       (from trigger detection to Cmd+V release).</li>
 *   <li><b>Snippet expansion speed</b> — average elapsed milliseconds per
 *       snippet expansion (backspaces + paste).</li>
 *   <li><b>JVM heap usage</b> — current used heap in bytes (via
 *       {@link MemoryMXBean}).</li>
 *   <li><b>Process CPU usage</b> — recent CPU load of this process (via
 *       {@link com.sun.management.OperatingSystemMXBean}).</li>
 *   <li><b>DB file size</b> — size in bytes of the SQLite database file,
 *       representing total stored data (history + snippets + config).</li>
 *   <li><b>Total clipboard entry payload</b> — sum of UTF-8 byte lengths of
 *       all cached clipboard entry contents.</li>
 *   <li><b>Total snippet payload</b> — sum of UTF-8 byte lengths of all
 *       cached snippet values.</li>
 * </ul>
 *
 * <p>All counters are updated by {@link PasteService} via {@link #recordPaste}
 * and {@link #recordSnippetExpansion}. The dashboard dialog polls
 * {@link #snapshot()} periodically to refresh its display.
 */
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private static final String DB_PATH =
            System.getProperty("user.home") + "/Library/Application Support/Pasties/pasties.db";

    // Paste operation counters
    private final AtomicLong pasteCount        = new AtomicLong(0);
    private final AtomicLong pasteTotalMs      = new AtomicLong(0);

    // Snippet expansion counters
    private final AtomicLong snippetCount      = new AtomicLong(0);
    private final AtomicLong snippetTotalMs    = new AtomicLong(0);

    // JVM MBeans (obtained once; they are thread-safe)
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

    // Live references to service caches (set after construction)
    private volatile ClipboardService clipboardService;
    private volatile SnippetService snippetService;

    /**
     * Wires in the live service references needed for payload size metrics.
     * Called once from {@link com.pasties.Main} after all services are constructed.
     */
    public void setServices(ClipboardService clipboardService, SnippetService snippetService) {
        this.clipboardService = clipboardService;
        this.snippetService   = snippetService;
    }

    // ---- Recording methods (called by PasteService) ----

    /**
     * Records the duration of a single paste-from-history operation.
     *
     * @param elapsedMs elapsed wall-clock milliseconds from start to completion
     */
    public void recordPaste(long elapsedMs) {
        pasteCount.incrementAndGet();
        pasteTotalMs.addAndGet(elapsedMs);
    }

    /**
     * Records the duration of a single snippet expansion operation.
     *
     * @param elapsedMs elapsed wall-clock milliseconds from start to completion
     */
    public void recordSnippetExpansion(long elapsedMs) {
        snippetCount.incrementAndGet();
        snippetTotalMs.addAndGet(elapsedMs);
    }

    // ---- Snapshot ----

    /**
     * Returns an immutable snapshot of all current metrics.
     * Safe to call from any thread; no blocking I/O except a single
     * {@link Files#size(Path)} call for the DB file size.
     *
     * @return current metrics snapshot
     */
    public Snapshot snapshot() {
        long usedHeapBytes = memoryBean.getHeapMemoryUsage().getUsed();

        double cpuLoad = -1.0;
        if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            cpuLoad = sunBean.getProcessCpuLoad();
        }

        long dbSizeBytes = 0;
        try {
            Path dbPath = Paths.get(DB_PATH);
            if (Files.exists(dbPath)) {
                dbSizeBytes = Files.size(dbPath);
            }
        } catch (Exception e) {
            log.debug("Could not read DB file size", e);
        }

        long clipboardPayloadBytes = computeClipboardPayload();
        long snippetPayloadBytes   = computeSnippetPayload();

        long pc  = pasteCount.get();
        long ptm = pasteTotalMs.get();
        long sc  = snippetCount.get();
        long stm = snippetTotalMs.get();

        return new Snapshot(
                pc  == 0 ? 0 : ptm / pc,    // avgPasteMs
                pc,
                sc  == 0 ? 0 : stm / sc,    // avgSnippetExpansionMs
                sc,
                usedHeapBytes,
                cpuLoad,
                dbSizeBytes,
                clipboardPayloadBytes,
                snippetPayloadBytes
        );
    }

    // ---- Helpers ----

    private long computeClipboardPayload() {
        ClipboardService cs = clipboardService;
        if (cs == null) return 0;
        List<ClipboardEntry> entries = cs.getCachedHistory();
        long total = 0;
        for (ClipboardEntry e : entries) {
            total += e.content().getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private long computeSnippetPayload() {
        SnippetService ss = snippetService;
        if (ss == null) return 0;
        List<Snippet> snippets = ss.getAllCached();
        long total = 0;
        for (Snippet s : snippets) {
            total += s.value().getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    // ---- Snapshot record ----

    /**
     * Immutable point-in-time metrics snapshot.
     *
     * @param avgPasteMs              average paste operation time (ms); 0 if no pastes recorded
     * @param pasteCount              total number of paste operations recorded
     * @param avgSnippetExpansionMs   average snippet expansion time (ms); 0 if none recorded
     * @param snippetExpansionCount   total number of snippet expansions recorded
     * @param heapUsedBytes           JVM heap used bytes at time of snapshot
     * @param processCpuLoad          JVM process CPU load [0.0, 1.0], or -1 if unavailable
     * @param dbFileSizeBytes         SQLite DB file size in bytes
     * @param clipboardPayloadBytes   total UTF-8 bytes of cached clipboard entries
     * @param snippetPayloadBytes     total UTF-8 bytes of all snippet values
     */
    public record Snapshot(
            long avgPasteMs,
            long pasteCount,
            long avgSnippetExpansionMs,
            long snippetExpansionCount,
            long heapUsedBytes,
            double processCpuLoad,
            long dbFileSizeBytes,
            long clipboardPayloadBytes,
            long snippetPayloadBytes
    ) {}
}
