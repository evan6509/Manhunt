"""Target selection and installable metadata regression tests."""
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

import test_release
from verify_mod_jar import verify


class TargetTests(unittest.TestCase):
    def test_supported_targets_and_numeric_minimums(self):
        from minecraft_target import target
        self.assertEqual(target('26.2')['fabric_version'], '0.154.2+26.2')
        self.assertEqual(target('26.3')['fabric_version'], '0.165.0+26.3')
        self.assertEqual(target('26.3')['fabric_minimum'], '>=0.165.0')
        self.assertEqual(target('26.3')['loader_minimum'], '>=0.19.3')
        with self.assertRaises(ValueError):
            target('26.4')

    def test_both_target_metadata_and_wrong_dependency_rejected(self):
        for minecraft, minimum in [('26.2', '>=0.154.2'), ('26.3', '>=0.165.0')]:
            for key, wrong in [(None, None), ('fabric-api', '>=0.1.0'),
                               ('fabricloader', '>=0.1.0'), ('minecraft', '26.4')]:
                with self.subTest(minecraft=minecraft, key=key), tempfile.TemporaryDirectory() as folder:
                    original = test_release.JarTests().fixture(folder)
                    with ZipFile(original) as archive:
                        entries = {name: archive.read(name) for name in archive.namelist()}
                    original.unlink()
                    metadata = json.loads(entries['fabric.mod.json'])
                    metadata['version'] = f'1.0.1+mc{minecraft}'
                    metadata['depends'].update({'minecraft': minecraft, 'fabric-api': minimum,
                                                'fabricloader': '>=0.19.3'})
                    if key:
                        metadata['depends'][key] = wrong
                    entries['fabric.mod.json'] = json.dumps(metadata)
                    jar = Path(folder) / f'manhunt-1.0.1+mc{minecraft}.jar'
                    with ZipFile(jar, 'w') as archive:
                        for name, data in entries.items():
                            archive.writestr(name, data)
                    if key:
                        with self.assertRaises(ValueError):
                            verify(folder, '1.0.1', minecraft=minecraft)
                    else:
                        self.assertEqual(verify(folder, '1.0.1', minecraft=minecraft)[0], jar)

    def test_deployment_contains_both_target_adapters(self):
        from deployment_archive import allowed_source
        for minecraft in ('26.2', '26.3'):
            self.assertTrue(allowed_source(f'src/{minecraft}/java/com/evanchubbuck/manhunt/MinecraftCompat.java'))
        self.assertFalse(allowed_source('src/26.4/java/private.java'))

    def test_docker_explicitly_builds_262(self):
        docker = Path('Dockerfile').read_text()
        self.assertIn('-Pminecraft_version=26.2', docker)

    def test_release_requires_both_fabric_targets(self):
        from test_release import ReleaseAssetsTests
        from verify_release_assets import verify as verify_assets
        with tempfile.TemporaryDirectory() as folder:
            _, assets = ReleaseAssetsTests().fixture(folder)
            second = assets / 'manhunt-1.0.1+mc26.3.jar'
            if second.exists():
                second.unlink()
            with self.assertRaises(ValueError):
                verify_assets(assets, '1.0.1')
