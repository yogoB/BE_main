"""Runnable checks for readiness validation and secret-safe failure output."""
import base64
from pathlib import Path
import subprocess
import sys
import tempfile
from check_auth_config import DEFAULTS, check, load

valid = dict(DEFAULTS, JWT_SECRET=base64.b64encode(b'not-a-real-key-only-test-material!!').decode(),
             AUTH_RETURN_URL='https://app.example.com/account.html', AUTH_EMAIL_LINK_URL='https://app.example.com/account.html',
             GOOGLE_AUTH_ENABLED='true', GOOGLE_CLIENT_ID='test-client', GOOGLE_CLIENT_SECRET='test-google-secret',
             GOOGLE_REDIRECT_URI='https://app.example.com/login/oauth2/code/google',
             AUTH_EMAIL_ENABLED='true', SMTP_HOST='smtp.example.com', SMTP_USERNAME='test-user', SMTP_PASSWORD='test-mail-secret',
             AUTH_EMAIL_FROM='noreply@example.com', YOGOBI_CORS_ALLOWED_ORIGINS='https://app.example.com')
assert not check(valid)
attacks = {'JWT_SECRET': ['short', valid['SMTP_PASSWORD']], 'GOOGLE_AUTH_ENABLED': ['false'],
           'GOOGLE_CLIENT_SECRET': [''], 'AUTH_EMAIL_ENABLED': ['false'], 'SMTP_HOST': [''], 'SMTP_PASSWORD': [''],
           'AUTH_SECURE_COOKIES': ['false', 'yes'], 'AUTH_SESSION_COOKIE_NAME': ['JSESSIONID'],
           'AUTH_EMAIL_FROM': ['bad\nBcc:secret@example.com'], 'SMTP_AUTH': ['false'], 'SMTP_STARTTLS_REQUIRED': ['false'],
           'SMTP_PORT': ['abc', '0', '65536'],
           'GOOGLE_REDIRECT_URI': ['https://app.example.com/other', 'https://app.example.com/login/oauth2/code/google?x=1'],
           'AUTH_EMAIL_LINK_URL': ['https://user:pass@app.example.com/account.html', 'https://app.example.com/#token=secret'],
           'YOGOBI_CORS_ALLOWED_ORIGINS': ['*', 'https://*.example.com', 'https://app.example.com/path', 'http://app.example.com']}
count = 1
for key, replacements in attacks.items():
    for value in replacements:
        assert check(dict(valid, **{key: value})), key
        count += 1
local = dict(valid, AUTH_SECURE_COOKIES='false', AUTH_SESSION_COOKIE_NAME='YGB_SESSION',
             AUTH_RETURN_URL='http://localhost:8080/account.html', AUTH_EMAIL_LINK_URL='http://localhost:8080/account.html',
             GOOGLE_REDIRECT_URI='http://localhost:8080/login/oauth2/code/google',
             YOGOBI_CORS_ALLOWED_ORIGINS='http://localhost:8080', SMTP_HOST='localhost', SMTP_AUTH='false', SMTP_STARTTLS_REQUIRED='false')
assert not check(local, local=True)
assert check(local)
assert check(dict(valid, AI_INTERNAL_TOKEN=valid['JWT_SECRET']))
with tempfile.TemporaryDirectory() as directory:
    config = Path(directory)/'.env'
    config.write_text('\n'.join(f'{key}={value}' for key, value in valid.items()))
    assert load(config, {'SMTP_PORT': '2525'})['SMTP_PORT'] == '2525'
    result = subprocess.run([sys.executable, str(Path(__file__).with_name('check_auth_config.py')), '--env-file', str(config)],
                            env={}, text=True, capture_output=True)
    assert result.returncode == 0, result.stdout
    assert all(value not in result.stdout for value in (valid['JWT_SECRET'], valid['GOOGLE_CLIENT_SECRET'], valid['SMTP_PASSWORD']))
    config.write_text('JWT_SECRET=${accidental-secret}')
    result = subprocess.run([sys.executable, str(Path(__file__).with_name('check_auth_config.py')), '--env-file', str(config)],
                            env={}, text=True, capture_output=True)
    assert result.returncode == 1 and 'accidental-secret' not in result.stdout
print(f'Auth config: {count + 6} scenarios passed')
