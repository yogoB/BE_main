-- G-76 e. 비회원 퍼널 행위자 키를 원문 IP 에서 SHA-256 으로 바꾼다.
-- 처리방침은 "원문 IP 를 저장하지 않는다"고 적는데 funnel_event 에는 'ip:<원문>' 이 남아 있었다.
-- 앞으로의 키(FunnelCounter.actor)와 같은 값이라 같은 사람은 계속 같은 사람으로 센다.
-- 이미 해시인 행(ip: + 64자)은 건드리지 않는다 — 두 번 돌려도 같은 결과다.
UPDATE funnel_event
   SET actor_key = 'ip:' || encode(sha256(convert_to(substr(actor_key, 4), 'UTF8')), 'hex')
 WHERE actor_key LIKE 'ip:%'
   AND length(actor_key) <> 3 + 64;
