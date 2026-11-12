#!/usr/bin/env python3
"""Prepare a product-specific Info.plist without changing the shared Xcode source."""
import argparse
import plistlib
from pathlib import Path
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--source', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--product', required=True)
parser.add_argument('--url', required=True)
args = parser.parse_args()
with args.source.open('rb') as stream:
    info = plistlib.load(stream)
info.pop('WKAppBoundDomains', None)
if args.product == 'hovel':
    address = urlsplit(args.url)
    if not address.hostname or address.scheme not in ('https', 'http'):
        parser.error('Hovel needs a valid web origin')
    info['WKAppBoundDomains'] = [address.hostname]
    # The name under the icon and in the system's permission prompts.
    info['CFBundleDisplayName'] = 'Hovel'
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('wb') as stream:
    plistlib.dump(info, stream)
