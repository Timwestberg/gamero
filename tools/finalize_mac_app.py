"""Finalize only Gradle-generated app images; never re-sign the vendor runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile


def tree_digest(root):
    digest = hashlib.sha256()
    def visit(path):
        info = path.lstat()
        digest.update(json.dumps([str(path.relative_to(root)), stat.S_IMODE(info.st_mode),
                                  stat.S_IFMT(info.st_mode)], separators=(',', ':')).encode())
        if path.is_symlink():
            digest.update(os.readlink(path).encode())
        elif path.is_dir():
            for child in sorted(path.iterdir()):
                visit(child)
        elif path.is_file():
            digest.update(str(info.st_size).encode() + b'\0')
            with path.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
        else:
            raise RuntimeError(f'Unsupported bundle entry: {path}')
    visit(root)
    return digest.hexdigest()


def run(command):
    subprocess.run([str(value) for value in command], check=True)


def verify_signature(path, execute):
    execute(['/usr/bin/codesign', '--verify', '--deep', '--strict', path])


def paths(app, source, output_root, manifest):
    app, source, output_root, manifest = map(Path, (app, source, output_root, manifest))
    if app.is_symlink() or app.suffix != '.app' or app.resolve().parent != output_root.resolve():
        raise RuntimeError('Finalization requires an app directly inside its Gradle output directory')
    app, source = app.resolve(strict=True), source.resolve(strict=True)
    if source == app or app in source.parents or source in app.parents:
        raise RuntimeError('Vendor runtime and generated app must be separate trees')
    if any(bundle == manifest.resolve() or bundle in manifest.resolve().parents for bundle in (app, source)):
        raise RuntimeError('Signature manifest must remain outside the signed app and vendor tree')
    contents = app / 'Contents'
    runtime = contents / 'runtime'
    if contents.is_symlink() or runtime.is_symlink() or not runtime.is_dir():
        raise RuntimeError('Generated app must contain an owned runtime directory')
    return app, source, runtime, manifest


def finalize(app, source, output_root, manifest, entitlements=None, execute=run):
    app, source, runtime, manifest = paths(app, source, output_root, manifest)
    # A failed build must not leave a prior successful seal receipt.
    manifest.unlink(missing_ok=True)
    verify_signature(source, execute)
    vendor_digest = tree_digest(source)
    shutil.rmtree(runtime)
    execute(['/usr/bin/ditto', '--rsrc', '--extattr', '--acl', source, runtime])
    if not (runtime / 'Contents/Frameworks').is_dir() or not (runtime / 'Contents/Home/lib/libjcef.dylib').is_file():
        raise RuntimeError('Complete JBR/JCEF bundle was not restored')
    if tree_digest(runtime) != vendor_digest:
        raise RuntimeError('Restored runtime differs from the vendor bundle')
    verify_signature(runtime, execute)
    command = ['/usr/bin/codesign', '--sign', '-', '--options', 'runtime', '--force']
    if entitlements:
        entitlement_path = Path(entitlements).resolve(strict=True)
        command += ['--entitlements', entitlement_path]
    # Intentionally no --deep: sign only our generated outer app after all copies/resources.
    execute(command + [app])
    if tree_digest(source) != vendor_digest or tree_digest(runtime) != vendor_digest:
        raise RuntimeError('Final signing changed the vendor runtime')
    for bundle in (source, runtime, app):
        verify_signature(bundle, execute)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    receipt = {'version': 1, 'app': str(app), 'source': str(source),
               'app_digest': tree_digest(app), 'vendor_digest': vendor_digest}
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=manifest.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(receipt, stream)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, manifest)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify(app, source, output_root, manifest, execute=run):
    app, source, runtime, manifest = paths(app, source, output_root, manifest)
    receipt = json.loads(manifest.read_text())
    if receipt.get('version') != 1 or receipt.get('app') != str(app) or receipt.get('source') != str(source):
        raise RuntimeError('Final signature receipt does not match this build')
    if tree_digest(app) != receipt['app_digest']:
        raise RuntimeError('Generated app changed after final signing')
    if any(tree_digest(bundle) != receipt['vendor_digest'] for bundle in (source, runtime)):
        raise RuntimeError('Vendor runtime changed after final signing')
    for bundle in (source, runtime, app):
        verify_signature(bundle, execute)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('finalize', 'verify'))
    for name in ('app', 'source-runtime', 'output-root', 'manifest'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--entitlements', type=Path)
    args = parser.parse_args()
    if args.operation == 'finalize':
        finalize(args.app, args.source_runtime, args.output_root, args.manifest, args.entitlements)
    else:
        verify(args.app, args.source_runtime, args.output_root, args.manifest)
    print('GAMERO_APP_FINAL_' + args.operation.upper() + '_VERIFIED')


if __name__ == '__main__':
    main()
