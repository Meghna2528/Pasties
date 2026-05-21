package com.pasties.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles all simulated paste operations using {@link Robot}.
 *
 * <h2>Two paste strategies</h2>
 * <dl>
 *   <dt>Clipboard paste ({@link #pasteText(String)})</dt>
 *   <dd>Used when the user selects an item from the clipboard history popup.
 *       Saves the current clipboard, writes the target text, sends Cmd+V,
 *       then restores the original clipboard content.</dd>
 *
 *   <dt>Snippet expansion ({@link #expandSnippet(int, String)})</dt>
 *   <dd>Used when a {@code /{key}} trigger is detected. First sends
 *       {@code eraseCount} backspaces to remove the typed trigger, then
 *       delegates to {@link #pasteText(String)} to insert the snippet value.</dd>
 * </dl>
 *
 * <h2>Re-entry prevention</h2>
 * <p>The {@code isProgrammaticPaste} flag is set to {@code true} before any
 * clipboard write and cleared afterwards. {@link com.pasties.hook.ClipboardMonitor}
 * checks this flag and skips recording when it is set, preventing the app's own
 * paste operations from polluting the clipboard history.
 *
 * <h2>Metrics</h2>
 * <p>Every paste and snippet expansion records its wall-clock duration in
 * {@link MetricsService} for display in the performance dashboard.
 *
 * <h2>Thread note</h2>
 * <p>{@link Robot#delay(int)} blocks the calling thread. Both public methods
 * must be called from a background thread — never from the EDT or the
 * JNativeHook callback thread.
 */
public class PasteService {

    private static final Logger log = LoggerFactory.getLogger(PasteService.class);

    /**
     * Delay in ms between clipboard write and Cmd+V keystroke.
     * 80ms is fast enough to feel instant while giving macOS time to
     * process the clipboard write before the paste keystroke fires.
     * Kept well under 300ms to avoid any perceptible lag.
     */
    private static final int PASTE_DELAY_MS = 80;

    /**
     * Shared flag read by {@link com.pasties.hook.ClipboardMonitor} to
     * suppress recording of programmatically-written clipboard content.
     */
    private final AtomicBoolean isProgrammaticPaste;

    private final MetricsService metricsService;
    private final Robot robot;

    public PasteService(AtomicBoolean isProgrammaticPaste,
                        MetricsService metricsService) {
        this.isProgrammaticPaste = isProgrammaticPaste;
        this.metricsService = metricsService;

        Robot r = null;
        try {
            r = new Robot();
            r.setAutoDelay(0);
        } catch (AWTException e) {
            log.error("Cannot create AWT Robot — Accessibility permission likely missing", e);
        }
        this.robot = r;
    }

    /**
     * Returns {@code true} if the {@link Robot} was successfully created.
     */
    public boolean isAvailable() {
        return robot != null;
    }

    /**
     * Pastes the given text into the currently focused application.
     *
     * <ol>
     *   <li>Saves the current clipboard contents.</li>
     *   <li>Sets {@code isProgrammaticPaste = true}.</li>
     *   <li>Writes {@code text} to the clipboard.</li>
     *   <li>Sends {@code Cmd+V} via {@link Robot}.</li>
     *   <li>Restores the original clipboard.</li>
     *   <li>Clears {@code isProgrammaticPaste}.</li>
     *   <li>Records elapsed time in {@link MetricsService}.</li>
     * </ol>
     *
     * <p><b>Must be called off the EDT.</b>
     *
     * @param text the text to paste (non-null)
     */
    public void pasteText(String text) {
        if (robot == null) {
            log.error("Robot not available; cannot paste text");
            return;
        }

        long start = System.currentTimeMillis();
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable saved = safeGetClipboardContents(clipboard);

        isProgrammaticPaste.set(true);
        try {
            clipboard.setContents(new StringSelection(text), null);
            robot.delay(PASTE_DELAY_MS);

            robot.keyPress(KeyEvent.VK_META);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_META);

            robot.delay(PASTE_DELAY_MS);
        } finally {
            restoreClipboard(clipboard, saved);
            isProgrammaticPaste.set(false);
            metricsService.recordPaste(System.currentTimeMillis() - start);
        }
    }

    /**
     * Erases {@code eraseCount} characters via backspace, then pastes
     * {@code snippetValue} into the currently focused application.
     *
     * <p>Used during {@code /{key}} snippet expansion. The eraseCount equals
     * the length of the trigger string (e.g. {@code "/addr"} → 5).
     *
     * <p><b>Must be called off the EDT.</b>
     *
     * @param eraseCount   number of characters to erase (trigger string length)
     * @param snippetValue the snippet text to insert
     */
    public void expandSnippet(int eraseCount, String snippetValue) {
        if (robot == null) {
            log.error("Robot not available; cannot expand snippet");
            return;
        }

        long start = System.currentTimeMillis();

        // Allow the key-typed event that completed the trigger to fully propagate
        robot.delay(PASTE_DELAY_MS);

        for (int i = 0; i < eraseCount; i++) {
            robot.keyPress(KeyEvent.VK_BACK_SPACE);
            robot.keyRelease(KeyEvent.VK_BACK_SPACE);
            robot.delay(20); // inter-key delay prevents dropped backspaces
        }

        pasteText(snippetValue);

        metricsService.recordSnippetExpansion(System.currentTimeMillis() - start);
    }

    // ---- Helpers ----

    private Transferable safeGetClipboardContents(Clipboard clipboard) {
        try {
            return clipboard.getContents(null);
        } catch (Exception e) {
            log.debug("Could not save current clipboard contents before paste", e);
            return null;
        }
    }

    private void restoreClipboard(Clipboard clipboard, Transferable saved) {
        if (saved == null) {
            return;
        }
        // Brief pause lets the paste keystroke complete before we overwrite the clipboard
        robot.delay(150);
        isProgrammaticPaste.set(true);
        try {
            clipboard.setContents(saved, null);
        } catch (Exception e) {
            log.warn("Could not restore clipboard after paste", e);
        }
    }
}
