-- 상품과 무관한 제보(화면·기능 오류, 기타). 상품을 지정하는 catalog_report 와 별도 표다.
-- 같은 원칙: 회원 ID·이메일·원문 IP 를 저장하지 않고, 본문은 90일 뒤 정기 파기한다.
CREATE TABLE service_report (
    id UUID PRIMARY KEY,
    category TEXT NOT NULL CHECK (category IN ('SYSTEM','OTHER')),
    description VARCHAR(2000) NOT NULL CHECK (btrim(description) <> ''),
    page_url VARCHAR(2000),
    source_url VARCHAR(2000),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RESOLVED','REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_service_report_created ON service_report(created_at);
