#!/usr/bin/env python3
"""Stop the local field build, retrieve its SQLite data, and verify the snapshot."""
import argparse
from datetime import datetime
from pathlib import Path
import sqlite3
import subprocess
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='adb device serial')
parser.add_argument('--output', type=Path, help='new output path (will not overwrite)')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = args.output or root / 'evidence' / ('qingqi-' + datetime.now().strftime('%Y%m%d-%H%M%S-') + uuid.uuid4().hex[:8] + '.sqlite')
out = out.expanduser().resolve()
out.parent.mkdir(parents=True, exist_ok=True)
adb = ['adb', '-s', args.serial]
# Force-stop prevents concurrent writes while copying. Already committed events are retained;
# the next launch marks any running session as interrupted. Never clear data or uninstall.
with out.open('xb') as target:
    try:
        subprocess.run([*adb, 'shell', 'am', 'force-stop', 'local.leo.qingqi'], check=True, capture_output=True)
        subprocess.run([*adb, 'exec-out', 'run-as', 'local.leo.qingqi', 'cat', 'databases/diagnostics.db'], stdout=target, stderr=subprocess.PIPE, check=True)
        target.flush()
        with sqlite3.connect(out.as_uri() + '?mode=ro', uri=True) as db:
            if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError('Downloaded database failed integrity check')
            sessions = db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0]
            events = db.execute('SELECT COUNT(*) FROM events').fetchone()[0]
    except BaseException:
        target.close()
        out.unlink(missing_ok=True)
        raise
print(f'Saved {out}\n{sessions} sessions, {events} events; integrity check passed.')
