package io.github.thgrcarvalho.zelo.application.email;

/** A plain-text transactional email. Plain text only — no HTML, to dodge spam heuristics. */
public record EmailMessage(String to, String subject, String body) {
}
