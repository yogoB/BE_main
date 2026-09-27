-- G-85 f. 공개 요청마다 AuthRateLimit 이 DELETE ... WHERE expires_at <= now() 로 만료 버킷을 지운다.
-- 평소 행이 적어 무해하지만 발신지를 바꿔 가며 보내면 버킷이 쌓이고 매 요청 전체 스캔이 된다.
CREATE INDEX IF NOT EXISTS idx_auth_rate_limit_expires ON auth_rate_limit(expires_at);
