package com.pasties.domain;

import java.time.Instant;

/**
 * Immutable value object representing a named text snippet.
 *
 * <p>The {@code keyName} must match {@code [a-zA-Z0-9_-]+} (validated in
 * {@link com.pasties.service.SnippetService} before persistence). When a
 * user types {@code /<keyName>} in any application, the app erases the trigger
 * and expands it to {@code value}.
 */
public record Snippet(
        long id,
        String keyName,
        String value,
        String description,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Returns the full trigger string that the user types, e.g. {@code "/addr"}.
     *
     * @param prefix the configured snippet prefix (default {@code "/"})
     * @return prefix + keyName
     */
    public String trigger(String prefix) {
        return prefix + keyName;
    }
}
