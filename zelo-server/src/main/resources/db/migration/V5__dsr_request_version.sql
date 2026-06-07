-- Optimistic-lock version for the DSR aggregate. Without it, concurrent status
-- transitions (fulfill-vs-fulfill, sweep-vs-fulfill, dispatch-vs-fulfill) are
-- blind read-modify-writes that can lost-update the terminal status and corrupt
-- the compliance record. @Version makes the losing UPDATE fail instead.
ALTER TABLE dsr_requests ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
