"""Check that the diagnostic preview cannot send vehicle commands or acquire credentials."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
permissions = {e.get(ns+'name') for e in manifest.findall('uses-permission')}
assert permissions == {'android.permission.BLUETOOTH_SCAN', 'android.permission.BLUETOOTH_CONNECT'}
sources = '\n'.join(p.read_text() for p in (root/'app/src/main/java').rglob('*.java'))
for forbidden in ['writeCharacteristic(', 'writeDescriptor(', 'createBond(', 'Runtime.getRuntime(', 'WebView(']:
    assert forbidden not in sources, forbidden
with tempfile.TemporaryDirectory() as tmp:
    subprocess.run(['javac','-d',tmp,str(root/'app/src/main/java/local/leo/qingqi/GattProfile.java'),str(root/'app/src/main/java/local/leo/qingqi/DiagnosticValues.java'),str(root/'tests/ProfileTest.java'),str(root/'tests/DiagnosticValuesTest.java')],check=True)
    subprocess.run(['java','-cp',tmp,'ProfileTest'],check=True)
    subprocess.run(['java','-cp',tmp,'DiagnosticValuesTest'],check=True)
subprocess.run(['python3',str(root/'tests/check_ble_flow.py')],check=True)
print('Read-only diagnostics and permission checks passed')
