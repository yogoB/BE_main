-- 변경 제안의 자동 검토 결과(D-29). 제안 내용을 사람이 눈으로 확인하는 대신
-- ① 스마트초이스 시세 ② AI 서버 LLM 조회 두 소스로 대조한다.
-- 둘 다 확인하지 못하면 UNVERIFIED — 막지 않고 통과시킨 뒤 사용자 제보(catalog_report)로 잡는다.
ALTER TABLE catalog_change_request
    ADD COLUMN review_status TEXT CHECK (review_status IN ('VERIFIED', 'MISMATCH', 'UNVERIFIED', 'SKIPPED')),
    ADD COLUMN review_detail TEXT CHECK (length(review_detail) <= 2000),
    ADD COLUMN reviewed_at TIMESTAMPTZ;
