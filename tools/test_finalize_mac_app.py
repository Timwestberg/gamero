"""Portable pipeline regressions; all macOS commands are replaced with a recording fake."""
from pathlib import Path
import shutil
import tempfile
import unittest
import finalize_mac_app as pipeline


class PackagingOrderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'generated'
        self.app = self.output / 'Smoke.app'
        self.runtime = self.app / 'Contents/runtime'
        self.runtime.mkdir(parents=True)
        self.source = self.root / 'vendor'
        (self.source / 'Contents/Frameworks/Framework.framework/Versions/A').mkdir(parents=True)
        (self.source / 'Contents/Frameworks/Framework.framework/Versions/Current').symlink_to('A')
        (self.source / 'Contents/Home/lib').mkdir(parents=True)
        (self.source / 'Contents/Home/lib/libjcef.dylib').write_bytes(b'vendor-signed-runtime')
        self.receipt = self.output / 'signature.json'
        self.entitlements = self.root / 'entitlements.plist'
        self.entitlements.write_text('fixture entitlements')
        self.commands = []

    def execute(self, command):
        command = [str(value) for value in command]
        self.commands.append(command)
        if command[0].endswith('/ditto'):
            shutil.copytree(command[-2], command[-1], symlinks=True)
        elif '--sign' in command:
            self.assertEqual(command[-1], str(self.app))
            self.assertNotIn('--deep', command)
            self.assertEqual(pipeline.tree_digest(self.source), pipeline.tree_digest(self.runtime))
            (self.app / 'Contents/outer-seal').write_text('generated app seal')

    def finalize(self, execute=None):
        pipeline.finalize(self.app, self.source, self.output, self.receipt, self.entitlements, execute or self.execute)

    def test_restore_then_outer_sign_then_verify_with_vendor_unchanged(self):
        before = pipeline.tree_digest(self.source)
        self.finalize()
        sign = next(i for i, command in enumerate(self.commands) if '--sign' in command)
        copy = next(i for i, command in enumerate(self.commands) if command[0].endswith('/ditto'))
        self.assertLess(copy, sign)
        self.assertEqual(sum('--sign' in command for command in self.commands), 1)
        self.assertIn(str(self.entitlements), self.commands[sign])
        self.assertEqual([command[-1] for command in self.commands[sign + 1:]],
                         [str(self.source), str(self.runtime), str(self.app)])
        self.assertEqual(before, pipeline.tree_digest(self.runtime))
        pipeline.verify(self.app, self.source, self.output, self.receipt, self.execute)

    def test_changes_after_final_signing_are_rejected_before_signature_probe(self):
        self.finalize()
        (self.app / 'Contents/late-resource').write_text('late build action')
        self.commands.clear()
        with self.assertRaisesRegex(RuntimeError, 'changed after final signing'):
            pipeline.verify(self.app, self.source, self.output, self.receipt, self.execute)
        self.assertEqual(self.commands, [])

    def test_nested_vendor_mutation_during_signing_fails_without_receipt(self):
        def mutate(command):
            self.execute(command)
            if '--sign' in command:
                (self.runtime / 'Contents/Home/lib/libjcef.dylib').write_text('bad recursive signer')
        with self.assertRaisesRegex(RuntimeError, 'changed the vendor runtime'):
            self.finalize(mutate)
        self.assertFalse(self.receipt.exists())

    def test_signature_failure_stops_pipeline_and_removes_stale_receipt(self):
        self.receipt.write_text('stale')
        def fail(command):
            if '--verify' in command:
                raise RuntimeError('signature rejected')
            self.execute(command)
        with self.assertRaisesRegex(RuntimeError, 'signature rejected'):
            self.finalize(fail)
        self.assertFalse(self.receipt.exists())
        self.assertEqual(self.commands, [])

    def test_outer_signature_failure_never_publishes_receipt(self):
        def fail_outer(command):
            self.execute(command)
            if '--verify' in command and str(command[-1]) == str(self.app):
                raise RuntimeError('outer seal rejected')
        with self.assertRaisesRegex(RuntimeError, 'outer seal rejected'):
            self.finalize(fail_outer)
        self.assertFalse(self.receipt.exists())

    def test_link_and_executable_mode_changes_after_signing_are_detected(self):
        self.finalize()
        library = self.runtime / 'Contents/Home/lib/libjcef.dylib'
        library.chmod(library.stat().st_mode ^ 0o100)
        with self.assertRaisesRegex(RuntimeError, 'changed after final signing'):
            pipeline.verify(self.app, self.source, self.output, self.receipt, self.execute)
        self.finalize()
        link = self.runtime / 'Contents/Frameworks/Framework.framework/Versions/Current'
        link.unlink()
        link.symlink_to('different-target')
        with self.assertRaisesRegex(RuntimeError, 'changed after final signing'):
            pipeline.verify(self.app, self.source, self.output, self.receipt, self.execute)

    def test_manifest_cannot_modify_vendor_tree(self):
        receipt = self.source / 'receipt.json'
        receipt.write_text('vendor content')
        before = pipeline.tree_digest(self.source)
        with self.assertRaisesRegex(RuntimeError, 'outside the signed app and vendor tree'):
            pipeline.finalize(self.app, self.source, self.output, receipt, execute=self.execute)
        self.assertEqual(before, pipeline.tree_digest(self.source))
        self.assertEqual(self.commands, [])

    def test_source_cannot_be_a_generated_app_ancestor(self):
        with self.assertRaisesRegex(RuntimeError, 'separate trees'):
            pipeline.finalize(self.app, self.root, self.output, self.receipt, execute=self.execute)
        self.assertEqual(self.commands, [])


if __name__ == '__main__':
    unittest.main()
