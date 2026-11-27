#!/usr/bin/env python3
"""Scoped HTTPS smoke against an explicitly configured test origin.

Login updates only the dedicated test admin's login/session state. Optional file checks
retain two tiny synthetic objects/metadata for review; no member, booking or order writes.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import struct
import urllib.error
import urllib.parse
import urllib.request
import zlib

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def protected_properties(path):
    if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise ValueError('protected regular configuration required')
    values = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            if key in values:
                raise ValueError('duplicate configuration key')
            values[key] = value
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path, help='0600 properties with origin, admin.username and admin.password')
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--allow-synthetic-uploads', action='store_true')
    args = parser.parse_args()
    config = protected_properties(args.config)
    configured = config.get('origin', '')
    target = urllib.parse.urlsplit(configured)
    if (target.scheme != 'https' or not target.hostname or target.username is not None
            or target.path not in ('', '/') or target.query or target.fragment
            or any(ord(char) <= 32 for char in configured)):
        parser.error('config origin must be a bare HTTPS origin')
    if not all(config.get(key) for key in ('admin.username', 'admin.password')):
        parser.error('config must contain the dedicated test admin credentials')
    origin = 'https://' + target.netloc
    args.report.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(args.report, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    report = {'type': 'real-cloud-application-https-smoke', 'origin': origin,
              'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'status': 'running', 'checks': [], 'syntheticObjects': [],
              'credentialsOrTokensEmitted': False, 'memberBookingOrderWrites': False,
              'wechatLoginOrDeviceTested': False, 'independentTlsProtocolCapture': False}
    token = None
    stage = 'health'
    opener = urllib.request.build_opener(NoRedirect())

    def request(path, body=None, auth=None, content_type='application/json', method=None):
        if not path.startswith('/') or path.startswith('//'):
            raise ValueError('same-origin path required')
        headers = {'Accept': 'application/json', 'Cache-Control': 'no-cache'}
        if auth:
            headers['Authorization'] = 'Bearer ' + auth
        if body is not None:
            headers['Content-Type'] = content_type
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
        req = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
        try:
            with opener.open(req, timeout=25) as response:
                return response.status, response.headers, response.read(6 * 1024 * 1024)
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read(10000)

    def api(path, body=None, auth=None, method=None):
        status, _, raw = request(path, body, auth, method=method)
        if status != 200:
            raise ValueError('unexpected HTTP status; body withheld')
        return json.loads(raw)

    def passed(name, **summary):
        report['checks'].append({'name': name, 'status': 'passed', **summary})

    def media_path(url):
        value = urllib.parse.urlsplit(url)
        if value.scheme + '://' + value.netloc != origin or not value.path.startswith('/media/'):
            raise ValueError('unexpected media origin/path')
        return value.path + ('?' + value.query if value.query else '')

    try:
        health = api('/health')
        if health.get('code') != 0 or health.get('data', {}).get('service') != 'pilates-server':
            raise ValueError('full business application is not serving this origin')
        passed('complete application health', httpStatus=200)
        stage = 'anonymous-authorization'
        for path in ['/api/auth/me', '/api/admin/members?pageNum=1&pageSize=1']:
            if api(path).get('code') != 40100:
                raise ValueError('anonymous management access was not rejected')
        passed('anonymous management and identity requests rejected', businessCode=40100)
        stage = 'capabilities'
        caps = api('/api/capabilities')
        data = caps.get('data', {})
        if caps.get('code') != 0 or data.get('bookingMode') != 'FREE' or any(
                data.get(key) is not False for key in ['packageSales', 'paymentInitiation', 'refunds', 'phoneBinding', 'wechatReminder']):
            raise ValueError('test launch capability boundary mismatch')
        passed('FREE booking and unconfigured paid/phone/notification features disabled')
        stage = 'admin-login'
        login = api('/api/auth/admin-login', {'username': config['admin.username'], 'password': config['admin.password']})
        if login.get('code') != 0:
            report['failureBusinessCode'] = login.get('code')
            raise ValueError('dedicated test admin login failed; values withheld')
        token = login['data']['token']
        if 'ADMIN' not in login['data'].get('roles', []):
            raise ValueError('expected dedicated administrator role')
        me = api('/api/auth/me', auth=token)
        if me.get('code') != 0 or 'ADMIN' not in me.get('data', {}).get('roles', []):
            raise ValueError('authenticated Redis-backed session unavailable')
        passed('dedicated admin login and Redis-backed authenticated session', roles=['ADMIN'])
        stage = 'admin-read'
        for path in ['/api/admin/courses?pageNum=1&pageSize=1', '/api/admin/members?pageNum=1&pageSize=1']:
            if api(path, auth=token).get('code') != 0:
                raise ValueError('authorized admin read failed')
        passed('admin course/member read APIs')
        stage = 'wrong-password'
        wrong = api('/api/auth/admin-login', {'username': 'probe_' + secrets.token_hex(8), 'password': secrets.token_urlsafe(24)})
        if wrong.get('code') != 40100:
            raise ValueError('incorrect credentials were not rejected')
        passed('incorrect synthetic login rejected', businessCode=40100)
        if args.allow_synthetic_uploads:
            stage = 'synthetic-file-upload'
            def chunk(kind, data):
                return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
            png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\x20\x40\x60')) + chunk(b'IEND', b'')
            for purpose in ['PUBLIC_ASSET', 'AVATAR']:
                boundary = 'acceptance-' + secrets.token_hex(12)
                payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + (f'\r\n--{boundary}--\r\n').encode()
                status, _, raw = request('/api/upload?purpose=' + purpose, payload, token,
                                         'multipart/form-data; boundary=' + boundary)
                result = json.loads(raw)
                if status != 200 or result.get('code') != 0:
                    report['failureBusinessCode'] = result.get('code')
                    raise ValueError('synthetic upload failed; response withheld')
                uploaded = result['data']
                report['syntheticObjects'].append({'id': uploaded['objectId'], 'purpose': purpose,
                    'bytes': len(png), 'sha256': hashlib.sha256(png).hexdigest(), 'retainedForReview': True})
                status, headers, content = request(media_path(uploaded['previewUrl']))
                if status != 200 or content != png:
                    raise ValueError('cloud object proxy read/hash mismatch')
                if purpose == 'AVATAR':
                    if 'no-store' not in headers.get('Cache-Control', ''):
                        raise ValueError('private response cache boundary mismatch')
                    if api('/media/public/' + uploaded['objectId']).get('code') != 40300:
                        raise ValueError('private object exposed on public media path')
                    if api('/api/files/' + uploaded['objectId'] + '/access').get('code') != 40100:
                        raise ValueError('anonymous private file access granted')
                    if api('/media/access/' + uploaded['objectId'] + '?expires=1&signature=invalid').get('code') != 40300:
                        raise ValueError('invalid private signed URL accepted')
            passed('real COS public/private synthetic upload, proxy read/hash and private boundary', objects=2)
        stage = 'logout'
        if api('/api/auth/logout', {}, token).get('code') != 0 or api('/api/auth/me', auth=token).get('code') != 40100:
            raise ValueError('Redis-backed logout revocation did not take effect')
        token = None
        passed('Redis-backed logout revokes token immediately')
        report['status'] = 'passed'
    except Exception as error:
        report.update(status='failed', failedStage=stage, errorType=type(error).__name__, rawErrorWithheld=True)
    finally:
        if token:
            try:
                report['cleanupLogoutSucceeded'] = api('/api/auth/logout', {}, token).get('code') == 0
            except Exception:
                report['cleanupLogoutSucceeded'] = False
        with os.fdopen(fd, 'w') as output:
            json.dump(report, output, indent=2)
            output.write('\n')
    print(json.dumps({'status': report['status'], 'passedChecks': len(report['checks']),
                      'failedStage': report.get('failedStage'), 'report': str(args.report)}))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
