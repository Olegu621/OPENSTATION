import argparse
import os
import ssl
import tempfile
import unittest
from urllib.error import URLError
from contextlib import contextmanager
from unittest.mock import patch
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import flash_service as f

class TransportTests(unittest.TestCase):
    def setUp(self):
        mocker = patch.object(f, 'prepare_base_partitions', return_value={name: name for name in f.BASE_PARTITION_FILES})
        mocker.start()
        self.addCleanup(mocker.stop)

    def test_pinned_rom_fallback_matches_published_manifest(self):
        import json
        from pathlib import Path
        manifest = json.loads((Path(f.REPO_ROOT) / 'rom' / 'rom_manifest.json').read_text(encoding='utf-8-sig'))
        self.assertEqual(f.PINNED_ROM_VERSION, manifest['version'])
        self.assertEqual(f.PINNED_ROM_NAME, manifest['archive']['fileName'])
        self.assertEqual(f.PINNED_ROM_SIZE, manifest['archive']['size'])
        self.assertEqual(f.PINNED_ROM_SHA256, manifest['archive']['sha256'])
        self.assertEqual(f.ROM_FILES, f._parse_rom_manifest_files(manifest))

    def test_rom_request_verifies_tls_with_the_bundled_roots(self):
        request = f.urllib.request.Request("https://api.github.com/")
        with patch.object(f.urllib.request, "urlopen") as urlopen:
            f._open_rom_url(request, timeout=20)
        context = urlopen.call_args.kwargs["context"]
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertGreater(context.cert_store_stats()["x509_ca"], 0)

    def test_invalid_rom_certificate_is_not_retried_without_tls_verification(self):
        request = f.urllib.request.Request("https://github.com/")
        error = URLError(ssl.SSLCertVerificationError("untrusted"))
        with patch.object(f.urllib.request, "urlopen", side_effect=error) as urlopen:
            with self.assertRaisesRegex(RuntimeError, "сертификат"):
                f._open_rom_url(request, timeout=20)
        urlopen.assert_called_once()

    def test_rom_release_is_found_after_ten_newer_releases(self):
        import io
        import json
        from pathlib import Path
        manifest = (Path(f.REPO_ROOT) / 'rom' / 'rom_manifest.json').read_bytes()
        releases = [{'tag_name': f'v1.9.{i}', 'assets': []} for i in range(10)]
        releases.append({'tag_name': f.PINNED_ROM_TAG, 'assets': [
            {'name': 'rom_manifest.json', 'browser_download_url': 'https://example.test/rom_manifest.json'},
            {'name': f.PINNED_ROM_NAME, 'browser_download_url': 'https://example.test/rom.zip'},
        ]})
        requests = []
        def urlopen(request, **kwargs):
            url = request.full_url
            requests.append(url)
            return io.BytesIO(manifest if url.endswith('rom_manifest.json') else json.dumps(releases).encode())
        with patch.object(f.urllib.request, 'urlopen', side_effect=urlopen):
            result = f._resolve_latest_rom_release()
        self.assertIn('per_page=100', requests[0])
        self.assertEqual(result['version'], f.PINNED_ROM_VERSION)
        self.assertEqual(result['files'], f.ROM_FILES)
        self.assertEqual(result['manifest_bytes'], manifest)

    def test_offline_rom_fallback_verifies_extracted_images(self):
        import hashlib
        import io
        import zipfile
        payloads = {'vbmeta.img': b'vbmeta', 'vendor.img': b'vendor', 'system.img': b'system'}
        expected = {name: (len(data), hashlib.sha256(data).hexdigest()) for name, data in payloads.items()}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, data in payloads.items():
                archive.writestr(name, data)
        archive_bytes = stream.getvalue()
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(f, '_resolve_latest_rom_release', side_effect=OSError('offline')), \
             patch.object(f, 'ROM_FILES', expected), \
             patch.object(f, 'PINNED_ROM_SIZE', len(archive_bytes)), \
             patch.object(f, 'PINNED_ROM_SHA256', hashlib.sha256(archive_bytes).hexdigest()), \
             patch.object(f.urllib.request, 'urlopen', side_effect=lambda *args, **kw: io.BytesIO(archive_bytes)):
            extracted = f.download_latest_rom(folder)
            f.verify_rom(extracted)
            with open(os.path.join(extracted, 'vendor.img'), 'wb') as image:
                image.write(b'broken')
            with self.assertRaisesRegex(RuntimeError, 'SHA-256 vendor.img'):
                f.verify_rom(extracted)

    def test_direct_transfer_checks_status(self):
        class Device:
            def __init__(self): self.blocks = []; self.commands = []
            def start_download(self, target, name, fmt, size, timeout):
                self.start = (target, name, fmt, size); return b'success'
            def write_download_block(self, seq, data, timeout): self.blocks.append((seq, len(data)))
            def bulkCmd(self, cmd, timeout): self.commands.append(cmd); return b'success'
        device = Device()
        @contextmanager
        def opened(): yield device
        with tempfile.NamedTemporaryFile(delete=False) as image:
            image.write(b'x' * 150000); path = image.name
        try:
            with patch.object(f, 'open_worldcup', opened):
                f.download_image('store', 'system', path)
                self.assertEqual(f.aml_bulkcmd('saveenv'), 'success')
            self.assertEqual(device.start, ('store', 'system', 'normal', 150000))
            self.assertEqual(device.blocks, [(0, 65536), (1, 65536), (2, 18928)])
            self.assertEqual(device.commands, ['download get_status', 'saveenv'])
        finally:
            os.unlink(path)

    def test_usb_probe_tries_next_backend_and_records_working_one(self):
        from pyamlboot_local import proto
        class Usb:
            def get_active_configuration(self): return object()
        class Device:
            def __init__(self, backend):
                if backend == 'bad': raise ValueError('device not found')
                self.dev = Usb()
            def identify(self): return ''.join(map(chr, (0, 7, 0, 16)))
            def bulkCmd(self, command, timeout):
                assert command == 'mmc dev 1'
                return b'success'
            def close(self): pass
        with patch.dict(os.environ, {}, clear=False), patch.object(f, 'check_usb_device', return_value={'usb_service': 'libusbk'}), patch.object(f, 'get_pyusb_backends', return_value=[('libusb0', 'bad'), ('libusb1', 'good')]), patch.object(proto, 'AmlogicSoC', side_effect=lambda backend: Device(backend)):
            self.assertTrue(f.probe_burn_mode())
            self.assertEqual(os.environ['OPENSTATION_USB_BACKEND'], 'libusb1')

    def test_bootrom_is_not_sent_burn_commands(self):
        from pyamlboot_local import proto
        class Device:
            def __init__(self): self.dev = self; self.calls = []
            def get_active_configuration(self): return object()
            def identify(self): return ''.join(map(chr, (3, 2, 0, 0)))
            def bulkCmd(self, *args): self.calls.append(args); return b'success'
            def close(self): pass
        device = Device()
        with patch.object(f, 'check_usb_device', return_value={}), patch.object(f, 'get_pyusb_backends', return_value=[('libusb1', object())]), patch.object(proto, 'AmlogicSoC', return_value=device):
            self.assertFalse(f.probe_burn_mode())
            self.assertEqual(device.calls, [])

    def test_bootstrap_runs_only_for_confirmed_bootrom(self):
        stages = [{'rom': (3, 2), 'stage': (0, 0), 'mode': 'bootrom'}] * 2
        with tempfile.TemporaryDirectory() as folder:
            open(os.path.join(folder, 'aml_00053.img'), 'wb').close()
            with patch.object(f, 'SCRIPT_DIR', folder), patch.object(f, 'probe_burn_mode', side_effect=[False, True]), patch.object(f, 'check_usb_device', return_value={'usb_service': 'libusb0'}), patch.object(f, 'probe_usb_identify', side_effect=stages), patch.object(f.subprocess, 'run', return_value=argparse.Namespace(returncode=0)) as worker, patch.object(f.time, 'sleep'):
                self.assertTrue(f.ensure_burn_mode())
                self.assertIn('--usb-boot-step', worker.call_args.args[0])
        with patch.object(f, 'probe_burn_mode', return_value=False), patch.object(f, 'check_usb_device', return_value={}), patch.object(f, 'probe_usb_identify', return_value={'rom': (0, 7), 'stage': (0, 16), 'mode': 'uboot'}), patch.object(f.subprocess, 'run') as worker:
            self.assertFalse(f.ensure_burn_mode())
            worker.assert_not_called()

    def test_bootstrap_initializes_g12_then_sends_valid_blocks(self):
        from pyamlboot_local import proto
        class Device:
            def __init__(self): self.dev = self; self.events = []; self.requests = iter([(3, 65536), (3, 65536)])
            def set_configuration(self): pass
            def identify(self): return ''.join(map(chr, (3, 2, 0, 0)))
            def writeSimpleMemory(self, addr, data): self.events.append(('ddr', int.from_bytes(data, 'little')))
            def writeLargeMemory(self, addr, data, size): self.events.append(('bl2', len(data)))
            def run(self, addr): self.events.append(('run', addr))
            def getBootAMLC(self): return next(self.requests)
            def writeAMLCData(self, seq, offset, data): self.events.append(('block', seq, offset, len(data)))
            def close(self): pass
        device = Device()
        with tempfile.NamedTemporaryFile(delete=False) as image:
            image.write(b'x' * 70000); path = image.name
        try:
            with patch.object(f, 'get_pyusb_backends', return_value=[('fake', object())]), patch.object(proto, 'AmlogicSoC', return_value=device), patch.object(f.time, 'sleep'):
                self.assertEqual(f.run_usb_boot_step_inproc(path), 0)
            self.assertEqual(device.events[:4], [('ddr', 0xb1), ('ddr', 0x5183), ('ddr', 0xb1), ('ddr', 0x5183)])
            self.assertEqual(device.events[4:], [('bl2', 65536), ('run', 0xfffa0000), ('block', 0, 65536, 3)])
        finally:
            os.unlink(path)

    def test_empty_usb_status_waits_for_confirmation_without_resending_block(self):
        from pyamlboot_local.proto import AmlogicSoC
        class Usb:
            def __init__(self, replies): self.replies = iter(replies); self.controls = 0; self.writes = 0
            def ctrl_transfer(self, *args): self.controls += 1
            def write(self, endpoint, data, timeout): self.writes += 1; return len(data)
            def read(self, endpoint, size, timeout): return next(self.replies)
        soc = AmlogicSoC.__new__(AmlogicSoC)
        soc.dev = Usb([b'', b'Continue:32', b'OK!!'])
        with patch.object(f.time, 'sleep'):
            soc.write_download_block(1978, b'example', 3000)
        self.assertEqual((soc.dev.controls, soc.dev.writes), (1, 1))
        soc.dev = Usb([b'', b'', b''])
        with patch.object(f.time, 'sleep'), self.assertRaisesRegex(IOError, 'write state is unknown'):
            soc.write_download_block(1978, b'example', 3000)
        self.assertEqual((soc.dev.controls, soc.dev.writes), (1, 1))

    def test_null_status_records_actual_read_size(self):
        from pyamlboot_local.proto import AmlogicSoC
        class Usb:
            def read(self, endpoint, size, timeout): return b"\0" * 8
        soc = AmlogicSoC.__new__(AmlogicSoC)
        soc.dev = Usb()
        with patch.object(f.time, "sleep"), self.assertRaisesRegex(IOError, "last USB read: 8 bytes"):
            soc._read_burn_status(3000)

    def test_misc_failure_runs_after_ubt_and_reports_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ('vbmeta.img', 'vendor.img', 'system.img'):
                open(os.path.join(folder, name), 'wb').close()
            calls = []
            def flash(name, *args):
                calls.append(name)
                if name == 'misc': raise RuntimeError('misc failed')
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=flash), patch.object(f, 'run_after_ubt', side_effect=lambda: calls.append('after_ubt')):
                with self.assertRaisesRegex(RuntimeError, 'misc failed'):
                    f.run_flash(argparse.Namespace(rom_dir=folder, revision='0008'))
            self.assertEqual(calls, ['boot', 'dtbo', 'odm', 'product', 'vbmeta', 'vendor', 'system', 'misc', 'after_ubt'])

    def test_after_ubt_runs_only_after_all_partitions_succeed(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ('vbmeta.img', 'vendor.img', 'system.img'):
                open(os.path.join(folder, name), 'wb').close()
            args_explicit = argparse.Namespace(rom_dir=folder, revision='00053')
            calls = []
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=lambda name, *args: calls.append(name)), patch.object(f, 'run_after_ubt', side_effect=lambda: calls.append('after_ubt')):
                f.run_flash(args_explicit)
            self.assertEqual(calls, ['boot', 'dtbo', 'odm', 'product', 'vbmeta', 'vendor', 'system', 'misc', 'after_ubt'])

            calls_auto = []
            args_auto = argparse.Namespace(rom_dir=folder, revision='auto')
            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=lambda name, *args: calls_auto.append(name)), patch.object(f, 'run_after_ubt', side_effect=lambda: calls_auto.append('after_ubt')):
                f.run_flash(args_auto)
            self.assertEqual(calls_auto, ['boot', 'dtbo', 'odm', 'product', 'vbmeta', 'vendor', 'system', 'after_ubt'])

            with patch.object(f, 'check_worldcup_driver', return_value=True), patch.object(f, 'verify_rom'), patch.object(f, 'ensure_burn_mode', return_value=True), patch.object(f, 'aml_bulkcmd'), patch.object(f, 'flash_partition', side_effect=RuntimeError('failed')), patch.object(f, 'run_after_ubt') as finish:
                with self.assertRaises(RuntimeError): f.run_flash(args_explicit)
                finish.assert_not_called()

    def test_verify_rom_uses_manifest_when_present_and_rejects_mismatch(self):
        import hashlib
        import json
        with tempfile.TemporaryDirectory() as folder:
            payloads = {
                'vbmeta.img': b'vbm-test-bytes',
                'vendor.img': b'vnd-test-bytes',
                'system.img': b'sys-test-bytes',
            }
            files_meta = {}
            for key, fn in (('vbmeta', 'vbmeta.img'), ('vendor', 'vendor.img'), ('system', 'system.img')):
                data = payloads[fn]
                with open(os.path.join(folder, fn), 'wb') as out:
                    out.write(data)
                files_meta[key] = {
                    'fileName': fn,
                    'size': len(data),
                    'sha256': hashlib.sha256(data).hexdigest(),
                }
            manifest_path = os.path.join(folder, 'rom_manifest.json')
            with open(manifest_path, 'w', encoding='utf-8') as mf:
                json.dump({'version': '1.6.1', 'files': files_meta}, mf)

            f.verify_rom(folder)

            with open(os.path.join(folder, 'system.img'), 'wb') as out:
                out.write(b'corrupted!!!!!')
            with self.assertRaisesRegex(RuntimeError, 'SHA-256 system.img'):
                f.verify_rom(folder)

    def test_ensure_burn_mode_resets_usb_and_falls_back_to_update_bl2_boot(self):
        with tempfile.TemporaryDirectory() as folder:
            open(os.path.join(folder, 'aml_00053.img'), 'wb').close()
            open(os.path.join(folder, 'update.exe'), 'wb').close()
            bootrom = {'rom': (3, 2), 'stage': (0, 0), 'mode': 'bootrom'}
            calls = []

            def fake_run(cmd, **kwargs):
                calls.append(cmd)
                if '--usb-boot-step' in cmd:
                    return argparse.Namespace(returncode=1, stdout=b'', stderr=b'')
                if len(cmd) >= 2 and cmd[1] == 'bl2_boot':
                    return argparse.Namespace(returncode=0, stdout=b'ok', stderr=b'')
                return argparse.Namespace(returncode=0, stdout=b'', stderr=b'')

            with patch.object(f, 'SCRIPT_DIR', folder), \
                 patch.object(f, 'probe_burn_mode', side_effect=[False, False, False, False, False, False, False, True]), \
                 patch.object(f, 'check_usb_device', return_value={'usb_service': 'libusb0'}), \
                 patch.object(f, 'probe_usb_identify', return_value=bootrom), \
                 patch.object(f, 'warn_conflicting_usb_tools'), \
                 patch.object(f, 'reset_usb_device', return_value=True) as reset_mock, \
                 patch.object(f, 'is_admin', return_value=False), \
                 patch.object(f.subprocess, 'run', side_effect=fake_run), \
                 patch.object(f.time, 'sleep'):
                self.assertTrue(f.ensure_burn_mode())
            reset_mock.assert_called()
            self.assertTrue(any('--usb-boot-step' in c for c in calls))
            self.assertTrue(any(len(c) >= 2 and c[1] == 'bl2_boot' for c in calls))

    def test_resolve_misc_image_selects_revision_specific_image(self):
        p_0008 = f.resolve_misc_image('0008')
        p_00052 = f.resolve_misc_image('00052')
        p_00053 = f.resolve_misc_image('00053')
        p_auto = f.resolve_misc_image('auto')
        p_skip = f.resolve_misc_image('skip')
        self.assertEqual(os.path.basename(p_0008), 'misc.img')
        self.assertEqual(os.path.basename(p_00052), 'misc.img')
        self.assertEqual(os.path.basename(p_00053), 'misc_00053.img')
        self.assertIsNone(p_auto)
        self.assertIsNone(p_skip)

    def test_bootrom_password_required_aborts_before_bl2_write(self):
        from pyamlboot_local import proto
        class Device:
            def __init__(self, ident):
                self.dev = self
                self._ident = ident
                self.events = []
            def set_configuration(self): pass
            def identify(self): return self._ident
            def writeSimpleMemory(self, *args): self.events.append('ddr')
            def writeLargeMemory(self, *args): self.events.append('bl2')
            def run(self, *args): self.events.append('run')
            def close(self): pass

        with tempfile.NamedTemporaryFile(delete=False) as image:
            image.write(b'x' * 70000)
            path = image.name
        try:
            locked = ''.join(map(chr, (3, 2, 0, 0, 1, 0, 0, 0)))
            dev_locked = Device(locked)
            with patch.object(f, 'get_pyusb_backends', return_value=[('fake', object())]), \
                 patch.object(proto, 'AmlogicSoC', return_value=dev_locked), \
                 patch.object(f, 'emit'):
                rc = f.run_usb_boot_step_inproc(path)
            self.assertEqual(rc, f.EXIT_BOOTROM_PASSWORD)
            self.assertEqual(dev_locked.events, [])

            invalid = ''.join(map(chr, (3, 2, 0, 0, 7, 0, 0, 0)))
            dev_invalid = Device(invalid)
            with patch.object(f, 'get_pyusb_backends', return_value=[('fake', object())]), \
                 patch.object(proto, 'AmlogicSoC', return_value=dev_invalid), \
                 patch.object(f, 'emit'):
                rc_inv = f.run_usb_boot_step_inproc(path)
            self.assertEqual(rc_inv, f.EXIT_BOOTROM_PASSWORD)
            self.assertEqual(dev_invalid.events, [])

            # Live U-Boot may report arbitrary values in bytes 4..5; never block it.
            uboot_info = f.classify_usb_id(''.join(map(chr, (0, 7, 0, 16, 1, 0))))
            self.assertEqual(uboot_info['mode'], 'uboot')
            self.assertFalse(uboot_info['password_blocked'])
        finally:
            os.unlink(path)

    def test_ensure_burn_mode_and_yasta_stop_on_password_locked_bootrom(self):
        locked_ident = {
            'present': True,
            'rom': (3, 2),
            'stage': (0, 0),
            'mode': 'bootrom',
            'need_password': 1,
            'password_ok': 0,
            'password_blocked': True,
            'password_invalid': False,
        }
        with patch.object(f, 'probe_burn_mode', return_value=False), \
             patch.object(f, 'check_usb_device', return_value={}), \
             patch.object(f, 'probe_usb_identify', return_value=locked_ident), \
             patch.object(f.subprocess, 'run') as sub_run, \
             patch.object(f, 'emit'):
            self.assertFalse(f.ensure_burn_mode())
            sub_run.assert_not_called()

        with patch.object(f, 'is_uboot_alive', return_value=False), \
             patch.object(f, 'warn_conflicting_usb_tools'), \
             patch.object(f, 'check_usb_device', return_value={}), \
             patch.object(f, 'probe_usb_identify', return_value=locked_ident), \
             patch.object(f.subprocess, 'Popen') as popen, \
             patch.object(f, 'emit'):
            self.assertFalse(f.boot_uboot_via_yasta('00052'))
            popen.assert_not_called()

    def test_boot_g12_stops_with_exit_code_2_on_password_locked_bootrom(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('boot_g12_mod', os.path.join(f.SCRIPT_DIR, 'boot-g12.py'))
        bg = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bg)

        class Device:
            def __init__(self, ident):
                self.dev = self
                self._ident = ident
                self.wrote_bl2 = False
            def set_configuration(self): pass
            def identify(self): return self._ident
            def writeLargeMemory(self, *args): self.wrote_bl2 = True
            def close(self): pass

        dev = Device(''.join(map(chr, (3, 2, 0, 0, 1, 0, 0, 0))))
        with tempfile.NamedTemporaryFile(delete=False) as image:
            image.write(b'x' * 70000)
            path = image.name
        try:
            with patch.object(bg.pyamlboot, 'AmlogicSoC', return_value=dev), \
                 patch.object(bg, 'resolve_usb_backend', return_value=object()), \
                 patch.object(bg, 'get_image_candidates', return_value=[path, path]), \
                 patch.object(bg, 'parse_cmdline', return_value=argparse.Namespace(revision='00052', retries=2, binary=None)):
                self.assertEqual(bg.main(), 2)
                self.assertFalse(dev.wrote_bl2)

            dev_uboot = Device(''.join(map(chr, (0, 7, 0, 16, 1, 0))))
            with patch.object(bg.pyamlboot, 'AmlogicSoC', return_value=dev_uboot):
                self.assertTrue(bg.boot_single_image(object(), path, retries=1))
        finally:
            os.unlink(path)

if __name__ == '__main__': unittest.main()
