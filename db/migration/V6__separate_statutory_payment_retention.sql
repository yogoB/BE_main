-- payment_record contains imported subscription analysis data, not automatically statutory merchant records.
-- Eligible evidence is explicitly preserved before deletion; no FK to the member or the analysis copy.
CREATE TABLE retained_payment_record (
    payment_record_id BIGINT PRIMARY KEY,
    merchant_raw TEXT NOT NULL,
    service_id BIGINT,
    amount BIGINT NOT NULL CHECK (amount >= 0),
    paid_at DATE NOT NULL,
    source TEXT NOT NULL,
    legal_basis TEXT NOT NULL CHECK (length(btrim(legal_basis)) BETWEEN 1 AND 500),
    retention_start DATE NOT NULL,
    retain_until DATE NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (retention_start >= paid_at AND retain_until > retention_start)
);
CREATE INDEX idx_retained_payment_expiry ON retained_payment_record(retain_until);
COMMENT ON TABLE payment_record IS 'User-imported subscription analysis data. Confirmed statutory evidence must be preserved separately before deletion.';
COMMENT ON TABLE retained_payment_record IS 'Purpose-restricted evidence with confirmed legal basis/deadline; no account FK. Unlinking is not guaranteed anonymization.';
