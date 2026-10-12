"""Run the real BleProbe against deterministic Android fakes; no Bluetooth hardware required."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
"android.annotation.SuppressLint": 'public @interface SuppressLint { String[] value(); }',
"android.os.Looper": 'public class Looper { public static Looper getMainLooper(){return new Looper();} }',
"android.os.Build": 'public class Build { public static class VERSION { public static int SDK_INT = 33; } }',
"android.os.SystemClock": 'public class SystemClock { public static long elapsedRealtime(){return Handler.now;} }',
"android.os.Handler": r'''public class Handler {
 public static long now; private record Job(long at, Runnable action) {}
 private static final java.util.List<Job> jobs = new java.util.ArrayList<>();
 public Handler(Looper l) {} public void post(Runnable r){postDelayed(r,0);}
 public void postDelayed(Runnable r,long ms){jobs.add(new Job(now+ms,r));}
 public void removeCallbacks(Runnable r){jobs.removeIf(j->j.action()==r);}
 public static void reset(){jobs.clear();now=0;}
 public static void advance(long ms){long end=now+ms;int count=0;while(true){
 Job job=jobs.stream().filter(j->j.at()<=end).min(java.util.Comparator.comparingLong(Job::at)).orElse(null);
 if(job==null)break;if(++count>1000)throw new AssertionError("timer loop");jobs.remove(job);now=job.at();job.action().run();}now=end;}
}''',
"android.content.Context": 'public class Context { public Context getApplicationContext(){return this;} public <T> T getSystemService(Class<T> c){return c.cast(new android.bluetooth.BluetoothManager());} }',
"android.bluetooth.BluetoothManager": 'public class BluetoothManager { public BluetoothAdapter getAdapter(){return new BluetoothAdapter();} }',
"android.bluetooth.BluetoothAdapter": 'public class BluetoothAdapter { public boolean isEnabled(){return true;} public android.bluetooth.le.BluetoothLeScanner getBluetoothLeScanner(){return new android.bluetooth.le.BluetoothLeScanner();} public BluetoothDevice getRemoteDevice(String s){return new BluetoothDevice();} }',
"android.bluetooth.BluetoothDevice": r'''public class BluetoothDevice {
 public static final int TRANSPORT_LE=2; public int getBondState(){return 10;} public String getAddress(){return "00:11:22:33:44:55";}
 public BluetoothGatt connectGatt(android.content.Context c,boolean auto,BluetoothGattCallback cb,int t){return new BluetoothGatt(cb);}
}''',
"android.bluetooth.BluetoothProfile": 'public class BluetoothProfile { public static final int STATE_DISCONNECTED=0,STATE_CONNECTED=2; }',
"android.bluetooth.BluetoothGatt": r'''public class BluetoothGatt {
 public static final int GATT_SUCCESS=0; public static final java.util.List<BluetoothGatt> created=new java.util.ArrayList<>();
 public final BluetoothGattCallback cb; public boolean closed, rejectRead, readInFlight, signalInFlight;
 public int readCalls,signalCalls; public java.util.List<BluetoothGattService> services=new java.util.ArrayList<>();
 public BluetoothGatt(BluetoothGattCallback cb){this.cb=cb;created.add(this);}
 public boolean discoverServices(){return true;} public java.util.List<BluetoothGattService> getServices(){return services;}
 public boolean readCharacteristic(BluetoothGattCharacteristic c){if(readInFlight||signalInFlight)throw new AssertionError("overlapping GATT operation");readCalls++;readInFlight=!rejectRead;return !rejectRead;}
 public boolean readRemoteRssi(){if(readInFlight||signalInFlight)throw new AssertionError("overlapping GATT operation");signalCalls++;signalInFlight=true;return true;}
 public void disconnect(){} public void close(){closed=true;}
 public void read(BluetoothGattCharacteristic c,int status){read(c,new byte[]{50},status);}
 public void read(BluetoothGattCharacteristic c,byte[] value,int status){readInFlight=false;c.value=value;if(android.os.Build.VERSION.SDK_INT>=33)cb.onCharacteristicRead(this,c,value,status);else cb.onCharacteristicRead(this,c,status);android.os.Handler.advance(0);}
 public void signal(){signalInFlight=false;cb.onReadRemoteRssi(this,-65,0);android.os.Handler.advance(0);}
}''',
"android.bluetooth.BluetoothGattCallback": r'''public class BluetoothGattCallback {
 public void onConnectionStateChange(BluetoothGatt g,int s,int n){} public void onServicesDiscovered(BluetoothGatt g,int s){}
 public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v,int s){}
 public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int s){}
 public void onReadRemoteRssi(BluetoothGatt g,int r,int s){}
}''',
"android.bluetooth.BluetoothGattService": r'''public class BluetoothGattService {
 public final java.util.UUID id;public final java.util.List<BluetoothGattCharacteristic> chars=new java.util.ArrayList<>();
 public BluetoothGattService(String s){id=local.leo.qingqi.DiagnosticValues.uuid(s);}
 public java.util.UUID getUuid(){return id;}public int getInstanceId(){return 0;}public int getType(){return 0;}
 public java.util.List<BluetoothGattService> getIncludedServices(){return java.util.List.of();}
 public java.util.List<BluetoothGattCharacteristic> getCharacteristics(){return chars;}
}''',
"android.bluetooth.BluetoothGattCharacteristic": r'''public class BluetoothGattCharacteristic {
 public static final int PROPERTY_READ=2;private final BluetoothGattService service;private final java.util.UUID id;private final int flags;
 public BluetoothGattCharacteristic(BluetoothGattService s,String id,int flags){service=s;this.id=local.leo.qingqi.DiagnosticValues.uuid(id);this.flags=flags;s.chars.add(this);}
 public java.util.UUID getUuid(){return id;}public int getInstanceId(){return 0;}public int getProperties(){return flags;}
 public byte[] value=new byte[]{50};public BluetoothGattService getService(){return service;}public byte[] getValue(){return value;}
 public java.util.List<BluetoothGattDescriptor> getDescriptors(){return java.util.List.of();}
}''',
"android.bluetooth.BluetoothGattDescriptor": 'public class BluetoothGattDescriptor { public java.util.UUID getUuid(){return java.util.UUID.randomUUID();} }',
"android.bluetooth.le.ScanCallback": 'public class ScanCallback { public static final int SCAN_FAILED_SCANNING_TOO_FREQUENTLY=6; public void onScanResult(int t,ScanResult r){} public void onScanFailed(int e){} }',
"android.bluetooth.le.ScanSettings": 'public class ScanSettings { public static final int SCAN_MODE_LOW_LATENCY=2; public static class Builder { public Builder setScanMode(int m){return this;} public ScanSettings build(){return new ScanSettings();} } }',
"android.bluetooth.le.ScanResult": 'public class ScanResult { public boolean isConnectable(){return true;} public int getRssi(){return -60;} public int getPrimaryPhy(){return 1;} public int getSecondaryPhy(){return 0;} public ScanRecord getScanRecord(){return null;} public android.bluetooth.BluetoothDevice getDevice(){return new android.bluetooth.BluetoothDevice();} }',
"android.bluetooth.le.ScanRecord": r'''public class ScanRecord {
 public String getDeviceName(){return "test";}public java.util.List<String> getServiceUuids(){return java.util.List.of();}
 public int getTxPowerLevel(){return 0;} public android.util.SparseArray<byte[]> getManufacturerSpecificData(){return new android.util.SparseArray<>();}
 public java.util.Map<String,byte[]> getServiceData(){return java.util.Map.of();}public byte[] getBytes(){return new byte[0];}
}''',
"android.util.SparseArray": 'public class SparseArray<T> { public int size(){return 0;}public int keyAt(int i){return 0;}public T valueAt(int i){return null;} }',
"android.bluetooth.le.BluetoothLeScanner": 'public class BluetoothLeScanner { public void startScan(Object f,ScanSettings s,ScanCallback c){} public void stopScan(ScanCallback c){} }',
}

with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    files = []
    for name, body in STUBS.items():
        package, cls = name.rsplit('.', 1)
        path = folder / (name.replace('.', '/') + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + package + ';\n' + body)
        files.append(str(path))
    files += [str(ROOT / 'app/src/main/java/local/leo/qingqi' / name) for name in ('BleProbe.java', 'GattProfile.java', 'DiagnosticValues.java')]
    files.append(str(ROOT / 'tests/BleProbeFlowTest.java'))
    subprocess.run(['javac', '-d', tmp, *files], check=True)
    subprocess.run(['java', '-cp', tmp, 'BleProbeFlowTest'], check=True)
