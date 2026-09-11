#!/usr/bin/env python3
"""Read-only auth readiness check; prints field names, never values. No network or mail."""
import argparse
import base64
import binascii
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

LOOPBACK = {'localhost', '127.0.0.1'}
DEFAULTS = {
    'AUTH_SECURE_COOKIES': 'true', 'AUTH_SESSION_COOKIE_NAME': '__Host-YGB_SESSION',
    'AUTH_RETURN_URL': 'http://localhost:5173/', 'AUTH_EMAIL_LINK_URL': 'http://localhost:8080/account.html',
    'GOOGLE_AUTH_ENABLED': 'false', 'AUTH_EMAIL_ENABLED': 'false',
    'SMTP_PORT': '587', 'SMTP_AUTH': 'true', 'SMTP_STARTTLS_REQUIRED': 'true',
    'YOGOBI_CORS_ALLOWED_ORIGINS': 'http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,https://yogob.fly.dev',
}


def load(path, environment):
    values = dict(DEFAULTS)
    # .env is imported by Spring as Java properties, not executed as a shell script.
    # Fail closed on syntax outside the repository's plain KEY=value format.
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith(('#', '!')):
                continue
            key, sep, value = line.partition('=')
            if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key) or '\\' in value or '${' in value:
                raise ValueError('설정 파일은 단순 KEY=value 형식이어야 합니다. 값은 출력하지 않습니다.')
            values[key] = value.lstrip()
    values.update(environment)
    return values


def check(values, local=False):
    issues = []
    def need(condition, fields):
        if not condition:
            issues.append(fields)
    def url(key, raw=None, origin=False):
        try:
            raw = values.get(key, '') if raw is None else raw
            parsed = urlsplit(raw)
            valid = bool(parsed.hostname) and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
            valid = valid and not any(c.isspace() or c in '\\*' for c in raw)
            valid = valid and (parsed.scheme == 'https' or (local and parsed.scheme == 'http' and parsed.hostname in LOOPBACK))
            valid = valid and (parsed.port is None or 1 <= parsed.port <= 65535)
            valid = valid and (not origin or parsed.path == '')
            need(valid, key + ': 고정 HTTPS 주소 필요 (로컬 모드는 HTTP loopback 허용)')
            return parsed
        except ValueError:
            need(False, key + ': URL 형식 오류')
            return urlsplit('')
    secret = values.get('JWT_SECRET', '')
    try:
        need(len(base64.b64decode(secret, validate=True)) >= 32, 'JWT_SECRET: Base64 32바이트 이상 필요')
    except (ValueError, binascii.Error):
        need(False, 'JWT_SECRET: Base64 형식 오류')
    need(not secret or all(secret != values.get(k) for k in ('AI_INTERNAL_TOKEN', 'GOOGLE_CLIENT_SECRET', 'SMTP_PASSWORD')),
         'JWT_SECRET: 다른 용도의 비밀 값과 분리 필요')
    for key in ('GOOGLE_AUTH_ENABLED', 'AUTH_EMAIL_ENABLED'):
        need(values.get(key, '').lower() == 'true', key + ': 전체 회원 기능 검증에 활성화 필요')
    for key in ('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'SMTP_HOST', 'AUTH_EMAIL_FROM'):
        need(bool(values.get(key, '').strip()), key + ': 설정 필요')
    callback = url('GOOGLE_REDIRECT_URI')
    need(callback.path == '/login/oauth2/code/google', 'GOOGLE_REDIRECT_URI: 콜백 경로 불일치')
    browser_urls = [callback, url('AUTH_RETURN_URL'), url('AUTH_EMAIL_LINK_URL')]
    origins = values.get('YOGOBI_CORS_ALLOWED_ORIGINS', '').split(',')
    for raw in origins:
        browser_urls.append(url('YOGOBI_CORS_ALLOWED_ORIGINS', raw, origin=True))
    secure = values.get('AUTH_SECURE_COOKIES', '').lower() == 'true'
    need(values.get('AUTH_SECURE_COOKIES', '').lower() in ('true', 'false'), 'AUTH_SECURE_COOKIES: true/false 필요')
    if not secure:
        need(local and all(u.hostname in LOOPBACK for u in browser_urls), 'AUTH_SECURE_COOKIES: 운영에서는 true 필수')
    name = '__Host-YGB_SESSION' if secure else 'YGB_SESSION'
    need(values.get('AUTH_SESSION_COOKIE_NAME') == name, 'AUTH_SESSION_COOKIE_NAME: 쿠키 보안 모드와 이름 일치 필요')
    try:
        need(1 <= int(values.get('SMTP_PORT', '')) <= 65535, 'SMTP_PORT: 유효 포트 필요')
    except ValueError:
        need(False, 'SMTP_PORT: 정수 필요')
    for key in ('SMTP_AUTH', 'SMTP_STARTTLS_REQUIRED'):
        need(values.get(key, '').lower() in ('true', 'false'), key + ': true/false 필요')
        need(values.get(key, '').lower() == 'true' or (local and values.get('SMTP_HOST') in LOOPBACK),
             key + ': 외부 SMTP에서는 true 필수')
    if values.get('SMTP_AUTH', '').lower() == 'true':
        for key in ('SMTP_USERNAME', 'SMTP_PASSWORD'):
            need(bool(values.get(key, '').strip()), key + ': SMTP 인증 설정 필요')
    need(bool(re.fullmatch(r'[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+', values.get('AUTH_EMAIL_FROM', ''))),
         'AUTH_EMAIL_FROM: 단일 발신 주소 필요')
    return list(dict.fromkeys(issues))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=Path('.env'))
    parser.add_argument('--local', action='store_true', help='loopback 개발 설정 허용')
    args = parser.parse_args()
    try:
        issues = check(load(args.env_file, os.environ), args.local)
    except (OSError, UnicodeError, ValueError):
        print('FAIL: 설정 파일 읽기/형식 오류. 설정 값은 출력하지 않습니다.')
        return 1
    for issue in issues:
        print('FAIL:', issue)
    print(f'인증 설정 점검: {len(issues)}개 수정 필요' if issues else '인증 설정 정적 점검 통과')
    print('외부 검증 별도: 실제 Google 로그인, SMTP 배달, 브라우저 쿠키/CORS, 프록시 IP. 키의 무작위성은 검사하지 않습니다.')
    return int(bool(issues))


if __name__ == '__main__':
    raise SystemExit(main())
