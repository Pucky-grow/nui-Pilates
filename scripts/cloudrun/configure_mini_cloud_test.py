#!/usr/bin/env python3
"""Prepare cloud mini-program config; activate only after successful real login evidence."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ORIGIN = 'https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com'
APPID = 'wxd9bd556ad0c77104'
ENVIRONMENT = 'uni-test-d5gwbyymua6bf2ada'
SERVICE = 'uni-test'


def candidate_source(source, transport='cloudrun'):
    start = source.index('function resolveEnvironment(')
    if transport not in {'http', 'cloudrun'}:
        raise ValueError('unsupported candidate transport')
    target = (f"transport: 'cloudrun', cloudEnvironment: '{ENVIRONMENT}', "
              f"cloudService: '{SERVICE}', useMockLogin: false" if transport == 'cloudrun'
              else f"baseUrl: '{ORIGIN}', useMockLogin: false")
    return ('// 开发/体验连接隔离云测试服务；正式环境保持关闭，待生产验收后单独配置。\n'
            'const environments = {\n'
            f"  develop: {{ {target} }},\n"
            f"  trial: {{ {target} }},\n"
            "  release: { baseUrl: '', useMockLogin: false }\n"
            '};\n' + source[start:])


def validate_evidence(proof, transport='cloudrun'):
    required = {'appid', 'real-wx-login-code', 'cloud-code-exchange', 'authenticated-me',
                'logout-and-session-revocation', 'mock-code-rejection', 'cloud-health'}
    passed = {x.get('name') for x in proof.get('checks', []) if x.get('status') == 'passed'}
    expected_probe = ('devtools-native-wechat-to-cloud-login' if transport == 'cloudrun'
                      else 'devtools-real-wechat-to-cloud-login')
    if (proof.get('probeType') != expected_probe
            or proof.get('origin') != ORIGIN or proof.get('appid') != APPID
            or (transport == 'cloudrun' and (proof.get('transport') != 'cloudrun'
                or proof.get('environment') != ENVIRONMENT or proof.get('service') != SERVICE))
            or proof.get('status') != 'passed-scoped'
            or proof.get('realWechatLoginPassed') is not True
            or proof.get('negativeBoundaryPassed') is not True
            or required - passed):
        raise ValueError('STOP: successful real WeChat login and negative-boundary evidence required before activation')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate-directory', type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--transport', choices=['cloudrun', 'http'], default='cloudrun')
    args = parser.parse_args()
    path = ROOT / 'frontend/miniprogram/config/environment.js'
    source = candidate_source(path.read_text(), args.transport)
    if args.candidate_directory:
        args.candidate_directory.mkdir(parents=True, exist_ok=True)
        (args.candidate_directory / 'environment.js').write_text(source)
    if args.apply:
        if not args.evidence:
            parser.error('--apply requires --evidence')
        validate_evidence(json.loads(args.evidence.read_text()), args.transport)
        configurations = [ROOT / x for x in ['project.config.json', 'project.private.config.json',
                                             'frontend/miniprogram/project.config.json'] if (ROOT / x).exists()]
        updates = []
        for config in configurations:
            value = json.loads(config.read_text())
            value.setdefault('setting', {})['urlCheck'] = True
            updates.append((config, json.dumps(value, ensure_ascii=False, indent=2) + '\n'))
        path.write_text(source)
        for config, text in updates:
            config.write_text(text)
        print(json.dumps({'status': 'activated-cloud-test-only', 'transport': args.transport, 'releaseEnabled': False,
                          'domainCheckEnabled': True, 'miniProgramUploaded': False}))
    else:
        print(json.dumps({'status': 'candidate-only', 'transport': args.transport, 'activeProjectChanged': False,
                          'candidateDirectory': str(args.candidate_directory) if args.candidate_directory else None}))


if __name__ == '__main__':
    main()
