package io.github.thgrcarvalho.zelo.application.email;

/**
 * Published (inside the transaction, delivered AFTER_COMMIT) when an account's
 * email was just swapped — the heads-up that goes to the OLD address so a
 * hijacked-session change can't happen silently.
 */
public record EmailChangedNotice(String oldEmail, String newEmail) {

    @Override
    public String toString() {
        // Emails are B2B contact data but still personal — keep them out of logs.
        return "EmailChangedNotice[redacted]";
    }
}
