#!/usr/bin/env python3
"""Signed APK -> full APK + version.json -> optional COS publication.
No credentials are written to artifacts. APK objects are immutable; manifest uploads last.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlparse

DEFAULT_BASE = 'https://wataa-1259218212.cos.ap-guangzhou.myqcloud.com/'


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as src:
        for data in iter(lambda: src.read(1024 * 1024), b''):
            digest.update(data)
    return digest.hexdigest()


def run(tool, *args):
    # Windows Android SDK includes apksigner.bat; shell=False still launches .bat via Windows.
    result = subprocess.run([str(tool), *map(str, args)], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise ValueError(f'{Path(tool).name} 执行失败：{result.stdout}\n{result.stderr}')
    return result.stdout


def apk_info(apk, aapt, apksigner):
    badging = run(aapt, 'dump', 'badging', apk)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or not sdk:
        raise ValueError('无法读取 APK 包名、版本或最低 SDK')
    if "split='" in badging:
        raise ValueError('请使用单体 APK，不能发布拆分 APK')
    signature = run(apksigner, 'verify', '--verbose', '--print-certs', apk)
    if re.search(r'CN=Android Debug', signature, re.I):
        raise ValueError('拒绝发布 Android Debug 签名，请使用与用户已安装版本相同的正式签名')
    certs = sorted(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', signature))
    if not certs:
        raise ValueError('没有找到 APK 签名证书')
    return dict(packageName=package[1], versionCode=int(package[2]), versionName=package[3], minSdk=int(sdk[1]), certs=certs)


def build_release(apk, out, info_reader, notes='', base_url=DEFAULT_BASE):
    """Separated from SDK/COS for offline testing. Existing release directories are never overwritten."""
    info = info_reader(apk)
    digest = sha256(apk)
    release_key = f"releases/{info['versionCode']}-{digest[:16]}"
    if out.exists() and any(out.iterdir()):
        raise ValueError('输出目录非空，请使用新的目录，避免混合不同版本')
    out.mkdir(parents=True, exist_ok=True)
    folder = out / release_key
    folder.mkdir(parents=True)
    full = folder / 'app.apk'
    shutil.copyfile(apk, full)
    manifest = dict(schemaVersion=1, packageName=info['packageName'], versionCode=info['versionCode'],
                    versionName=info['versionName'], minSdk=info['minSdk'], notes=notes,
                    apk=dict(url=f'{release_key}/app.apk', size=full.stat().st_size, sha256=digest))
    (out / 'version.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (out / 'release-info.txt').write_text(f"APK SHA-256: {digest}\nManifest: {base_url.rstrip('/')}/version.json\nCertificate SHA-256: {', '.join(info['certs'])}\n", encoding='utf-8')
    return manifest


def upload(out, manifest, base_url):
    from qcloud_cos import CosConfig, CosS3Client
    host = urlparse(base_url).hostname or ''
    match = re.fullmatch(r'([a-z0-9-]+-\d+)\.cos\.([a-z0-9-]+)\.myqcloud\.com', host)
    if not match or urlparse(base_url).path not in ('', '/'):
        raise ValueError('--upload 需要 COS 存储桶根地址；自定义域名请自行上传产物')
    bucket, region = match.groups()
    config = CosConfig(Region=region, SecretId=os.environ['COS_SECRET_ID'], SecretKey=os.environ['COS_SECRET_KEY'],
                       Token=os.environ.get('COS_SESSION_TOKEN'), Scheme='https')
    client = CosS3Client(config)
    # Prevent accidental rollback/replacement of an already published version.
    try:
        response = client.get_object(Bucket=bucket, Key='version.json')
        stream = response['Body'].get_raw_stream()
        try:
            raw = stream.read(256 * 1024 + 1)
            if len(raw) > 256 * 1024:
                raise ValueError('云端清单异常大')
            current = json.loads(raw)
        finally:
            stream.close()
        if int(current['versionCode']) >= manifest['versionCode']:
            raise ValueError('云端已有相同或更高版本，请勿回滚或并行发布')
    except Exception as exc:
        from qcloud_cos.cos_exception import CosServiceError
        if not isinstance(exc, CosServiceError) or str(exc.get_status_code()) != '404':
            raise
    objects = [manifest['apk']]
    for entry in objects:
        path = out / entry['url']
        # Local SHA recheck catches accidental modification between generation and publication.
        if sha256(path) != entry['sha256']:
            raise ValueError('上传前文件 SHA-256 已变化')
        client.upload_file(Bucket=bucket, Key=entry['url'], LocalFilePath=str(path),
                           CacheControl='public, max-age=31536000, immutable',
                           ContentType='application/vnd.android.package-archive' if path.suffix == '.apk' else 'application/octet-stream',
                           Metadata={'sha256': entry['sha256']}, EnableMD5=True)
    # Last step: readers cannot see a new manifest referencing files still in flight.
    with (out / 'version.json').open('rb') as data:
        client.put_object(Bucket=bucket, Key='version.json', Body=data, ContentType='application/json; charset=utf-8',
                          CacheControl='no-store, max-age=0', EnableMD5=True)
    print(f"已发布：{base_url.rstrip('/')}/version.json")


def find_tool(name, explicit, sdk):
    if explicit:
        path = Path(explicit)
    else:
        suffix = '.bat' if name == 'apksigner' and os.name == 'nt' else '.exe' if os.name == 'nt' else ''
        roots = sorted((sdk / 'build-tools').glob('*'), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)), reverse=True)
        path = next((p / (name + suffix) for p in roots if (p / (name + suffix)).is_file()), None)
    if path is None or not path.is_file():
        raise ValueError(f'找不到 {name}，请配置 ANDROID_SDK_ROOT 或 --{name}')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path, help='已用原正式签名签好的单体 release APK')
    parser.add_argument('--out', required=True, type=Path)
    notes_group = parser.add_mutually_exclusive_group()
    notes_group.add_argument('--notes', default='', help='更新日志文字')
    notes_group.add_argument('--notes-file', type=Path, help='UTF-8 更新日志文件，支持多行')
    parser.add_argument('--base-url', default=DEFAULT_BASE)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))))
    parser.add_argument('--aapt'); parser.add_argument('--apksigner')
    parser.add_argument('--upload', action='store_true', help='实际上传 COS；凭据只从环境变量读取')
    args = parser.parse_args()
    url = urlparse(args.base_url)
    if url.scheme != 'https' or not url.hostname or url.username or url.query or url.fragment:
        parser.error('--base-url 必须是 HTTPS 根地址')
    try:
        aapt = find_tool('aapt', args.aapt, args.sdk)
        signer = find_tool('apksigner', args.apksigner, args.sdk)
        notes = args.notes_file.read_text(encoding="utf-8-sig") if args.notes_file else args.notes
        if len(notes) > 8000:
            raise ValueError("更新日志最多 8000 个字符")
        result = build_release(args.apk, args.out,
                               lambda p: apk_info(p, aapt, signer), notes, args.base_url)
        print(f"已生成 {args.out / 'version.json'}，全量 APK；尚未发布")
        if args.upload:
            upload(args.out, result, args.base_url)
    except Exception as exc:
        print(f'发布失败：{exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
