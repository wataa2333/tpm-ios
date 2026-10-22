import hashlib
import json
from pathlib import Path
import random
import io
import tempfile
import unittest
from unittest.mock import patch

import publish_android as publisher


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = self.root / 'old.apk'
        self.new = self.root / 'new.apk'
        data = random.Random(57).randbytes(32000)
        self.old.write_bytes(data)
        self.new.write_bytes(data[:10000] + b'new-content' + data[11000:])

    def info(self, path):
        return dict(packageName='com.personal.tpm', versionCode=57 if path == self.old else 58,
                    versionName='0.57.0' if path == self.old else '0.58.0', minSdk=29, certs=['test-certificate'])

    def test_full_release_manifest_matches_apk_and_notes(self):
        out = self.root / 'release'
        manifest = publisher.build_release(self.new, out, self.info, '修复界面\n优化加载')
        self.assertEqual(manifest, json.loads((out / 'version.json').read_text(encoding='utf-8')))
        self.assertNotIn('patches', manifest)
        self.assertEqual('修复界面\n优化加载', manifest['notes'])
        apk = manifest['apk']
        self.assertEqual(self.new.read_bytes(), (out / apk['url']).read_bytes())
        self.assertEqual(apk['size'], self.new.stat().st_size)
        self.assertEqual(apk['sha256'], publisher.sha256(self.new))
        self.assertEqual([], list(out.rglob('*.bsdiff')))

    def test_nonempty_output_is_rejected(self):
        out = self.root / 'existing'
        out.mkdir()
        (out / 'keep.txt').write_text('keep')
        with self.assertRaises(ValueError):
            publisher.build_release(self.new, out, self.info)
        self.assertEqual('keep', (out / 'keep.txt').read_text())

    def test_reject_debug_signed_apk(self):
        def command(tool, *args):
            if tool == 'aapt':
                return "package: name='com.personal.tpm' versionCode='58' versionName='0.58.0'\nsdkVersion:'29'"
            return 'Signer #1 certificate DN: CN=Android Debug, O=Android, C=US\nSigner #1 certificate SHA-256 digest: abc'
        with patch.object(publisher, 'run', command), self.assertRaisesRegex(ValueError, 'Debug'):
            publisher.apk_info(self.new, 'aapt', 'apksigner')

    def test_cos_uploads_manifest_last_and_stops_on_failure(self):
        from qcloud_cos.cos_exception import CosServiceError
        out = self.root / 'cos'
        result = publisher.build_release(self.new, out, self.info)
        calls = []
        class Client:
            def get_object(self, **kwargs):
                raise CosServiceError('GET', 'not found', '404')
            def upload_file(self, **kwargs):
                calls.append(kwargs['Key'])
            def put_object(self, **kwargs):
                self.body = kwargs['Body'].read()
                calls.append(kwargs['Key'])
        client = Client()
        with patch.dict('os.environ', {'COS_SECRET_ID': 'test-only', 'COS_SECRET_KEY': 'test-only'}), patch('qcloud_cos.CosS3Client', return_value=client):
            publisher.upload(out, result, publisher.DEFAULT_BASE)
        self.assertEqual('version.json', calls[-1])
        self.assertEqual(2, len(calls))
        self.assertEqual(result, json.loads(client.body))
        calls.clear()
        with patch.dict('os.environ', {'COS_SECRET_ID': 'test-only', 'COS_SECRET_KEY': 'test-only'}), patch('qcloud_cos.CosS3Client', return_value=client), patch.object(client, 'upload_file', side_effect=RuntimeError('network')):
            with self.assertRaises(RuntimeError):
                publisher.upload(out, result, publisher.DEFAULT_BASE)
        self.assertNotIn('version.json', calls)

    def test_cos_rejects_existing_higher_version(self):
        out = self.root / 'cos'
        result = publisher.build_release(self.new, out, self.info)
        class Body:
            def get_raw_stream(self):
                return io.BytesIO(b'{"versionCode":999}')
        class Client:
            def get_object(self, **kwargs):
                return {'Body': Body()}
            def upload_file(self, **kwargs):
                raise AssertionError('must not upload')
        with patch.dict('os.environ', {'COS_SECRET_ID': 'test-only', 'COS_SECRET_KEY': 'test-only'}), patch('qcloud_cos.CosS3Client', return_value=Client()):
            with self.assertRaises(ValueError):
                publisher.upload(out, result, publisher.DEFAULT_BASE)


if __name__ == '__main__':
    unittest.main()
