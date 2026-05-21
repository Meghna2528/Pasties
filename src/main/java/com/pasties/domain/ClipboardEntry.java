package com.pasties.domain;

import java.time.Instant;

/**
 * Immutable value object representing one clipboard history entry.
 *
 * <p>Text-only in v1; binary/image support is a future concern. Deduplication
 * is performed via {@code contentHash} (SHA-256 hex) at the repository layer,
 * so identical copies of the same content produce a single entry with an
 * incremented {@code accessCount}.
 */
public record ClipboardEntry(
        long id,
        String content,
        String contentHash,
        Instant createdAt,
        int accessCount
) {

    /**
     * Returns a display-safe, single-line preview truncated to {@code maxChars}.
     * Newlines and carriage returns are collapsed to a single space.
     *
     * @param maxChars maximum number of characters in the result (must be &gt; 1)
     * @return truncated string, ending with {@code '…'} if truncated
     */
    public String preview(int maxChars) {
        String flat = content.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= maxChars) {
            return flat;
        }
        return flat.substring(0, maxChars - 1) + '\u2026';
    }
}
