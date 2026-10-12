import importlib.util
from pathlib import Path
import unittest
import shutil
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'scripts/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def test_version_contract(self):
        gradle = "versionName '0.1.5-field'\nversionCode 6"
        self.assertEqual(release.version_for_tag('v0.1.5-field', gradle), ('0.1.5-field', 6))
        for tag in ('0.1.5-field', 'v0.1.4-field', 'v01.1.5-field', 'v0.1.5-field/x', 'v0.1.5-field\n'):
            with self.assertRaises(ValueError):
                release.version_for_tag(tag, gradle)
        with self.assertRaises(ValueError):
            release.version_for_tag('v0.1.5-01', "versionName '0.1.5-01'\nversionCode 6")
        with self.assertRaises(ValueError):
            release.version_for_tag('v0.1.5-field', gradle.replace('versionCode 6', 'versionCode 0'))

    def test_signature_contract(self):
        fingerprint = 'ab' * 32
        output = f'Signer #1 certificate SHA-256 digest: {fingerprint}\n'
        release.check_signature(output, fingerprint.upper())
        for value in ('', output.replace('ab', 'cd'), output + output.replace('#1', '#2')):
            with self.assertRaises(ValueError):
                release.check_signature(value, fingerprint)

    def test_notes_are_version_scoped(self):
        notes = release.release_notes('0.1.5-field', '# 更新\n\n## 0.1.5-field\n\n- 新功能\n\n## 0.1.4-field\n- 旧功能\n')
        self.assertIn('新功能', notes)
        self.assertNotIn('旧功能', notes)
        with self.assertRaises(ValueError):
            release.release_notes('0.1.6-field', '## 0.1.5-field\n- 更新\n')

    def test_tag_must_match_checkout_and_main(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'scripts').mkdir()
            (root / 'app').mkdir()
            shutil.copyfile(release.__file__, root / 'scripts/release.py')
            (root / 'app/build.gradle').write_text("versionName '0.1.5-field'\nversionCode 6\n")
            (root / 'CHANGELOG.md').write_text('## 0.1.5-field\n- Test\n')

            def git(*args):
                subprocess.run(['git', '-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid',
                                *args], cwd=root, capture_output=True, check=True)

            def validate():
                return subprocess.run([sys.executable, 'scripts/release.py', 'validate', 'v0.1.5-field'],
                                      cwd=root, capture_output=True).returncode

            git('init', '-b', 'main')
            git('add', '.')
            git('commit', '-m', 'fixture')
            git('update-ref', 'refs/remotes/origin/main', 'HEAD')
            self.assertNotEqual(validate(), 0)  # Missing tag.
            git('tag', '-a', 'v0.1.5-field', '-m', 'fixture')
            self.assertEqual(validate(), 0)
            git('commit', '--allow-empty', '-m', 'later')
            self.assertNotEqual(validate(), 0)  # Checkout is not the tagged commit.
            git('tag', '-d', 'v0.1.5-field')
            git('tag', '-a', 'v0.1.5-field', '-m', 'fixture')
            self.assertNotEqual(validate(), 0)  # Tagged commit is not on remote main.


if __name__ == '__main__':
    unittest.main()
