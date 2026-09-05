# C2000 concurrent pressure and A2DP recovery test

This Android demo stresses the C2000 camera, WiFi transport, and CPU work while
the BLE diagnostic application remains connected to the BMS or inverter. It is
intended to distinguish a BLE link issue from contention caused by other system
services. Version 1.2 also monitors the WI-C100 A2DP profile and performs a
bounded, multi-round retry recovery when the media profile drops while the
headset transport is still online.

## Contents

- `C2000-Stress-Demo-v1.2.1-A2DP-Retry-Coexist-debug.apk`: installable debug
  APK. This retry build uses package `com.codex.c2000stressdemo.retry` and app
  label `C2000 压力测试-重试版`, so it can be installed beside the original
  `com.codex.c2000stressdemo` app.
- `frame_receiver.py`: a lightweight PC-side HTTP receiver for WiFi traffic.
- `app/`: Android source code, package `com.codex.c2000stressdemo`.

## A2DP disconnect recovery

The foreground `A2dpRecoveryService` keeps monitoring after the Activity goes
to the background. It locks onto the most recently connected or bonded
`WI-C100`, then listens for A2DP, HFP, ACL, and adapter state broadcasts.

When A2DP changes from connected/disconnecting to disconnected, recovery is
bounded and serialized:

1. Wait 1.2 seconds so a normal profile transition can settle.
2. Verify that the target is bonded and HFP/ACL has not also gone offline.
3. Try the Android 12 hidden `BluetoothA2dp.connect(device)` operation up to
   four times in one recovery round. A returned `true` only means the request
   was accepted; success is counted only after an A2DP `CONNECTED` state is
   observed.
4. If one round fails but HFP/ACL is still online, wait 15, 30, 60, then
   120 seconds before starting the next round. The initial round plus delayed
   retries are capped at five rounds total.
5. If recovery still fails, or if Bluetooth is off, the device is unbonded,
   HFP/ACL also drops, or the hidden API is unavailable, the service stops
   retrying and records the failure. Use `Bluetooth settings` for the public,
   user-mediated fallback.

`BluetoothA2dp.connect()` is not part of the public Android SDK. It is available
on Android 12 platform builds but an OEM hidden-API policy can reject it. A
production implementation should use a C2000 vendor API or a platform-signed
privileged component. The demo deliberately does not toggle the entire adapter:
an interrupted disable/enable sequence could leave Bluetooth off and would also
discard useful in-memory diagnostic state.

## Prepare the WiFi endpoint

1. Connect the PC and C2000 to the same WiFi access point. Prefer 2.4 GHz when
   reproducing same-band WiFi/BLE interference.
2. On the PC, open PowerShell in this directory and run:

   ```powershell
   python .\frame_receiver.py --port 8080
   ```

3. Find the PC's LAN IPv4 address with `ipconfig`. In the Android demo, set the
   endpoint to `http://PC_LAN_IPV4:8080/frame`, for example
   `http://192.168.1.100:8080/frame`.
4. Allow the Windows firewall prompt for private networks if it appears. The
   receiver prints request count, total data, and average Mbps every 50 POSTs.

The receiver discards received data after counting it, so disk I/O on the PC
does not distort the result.

## Test order

Keep the BLE diagnostic app connected in the background and record its BLE
status, application log, and any HCI disconnect reason before each phase.

1. Baseline: leave this demo idle for 5 minutes.
2. Camera only: tap `Open camera` and observe preview stability.
3. WiFi only: tap `WiFi transfer` with a 64 KB payload.
4. Camera frames: tap `Capture a frame every 0.5 seconds and transfer`.
5. CPU and transport: enable `WiFi multithread processing` and `Camera
   multithread processing`, starting at 4 threads each.
6. Full load: run all modes together, then repeat on 2.4 GHz and 5 GHz WiFi.

Use the demo's BLE panel as a coarse system GATT connection count. It cannot
replace HCI logs or the target BLE app's own disconnect callback, so correlate a
count decrease with the original app's reason code (such as `0x08`, `0x13`, or
`0x16`).

## Safety and interpretation

- Begin at 4 WiFi threads, 4 camera-processing threads, and 64 KB payloads.
  Increase one variable at a time. The controls are bounded so backlog is
  reported as dropped work instead of growing without limit.
- `Stop all pressure loads` stops camera capture, frame transfer, CPU processing,
  and WiFi send loops.
- A BLE failure only under 2.4 GHz traffic points toward RF coexistence or
  scheduling pressure. A failure under camera/CPU load with quiet WiFi points
  more toward power, thermal, system scheduling, or Bluetooth service behavior.
- This app observes system-wide GATT device-count changes. Android does not let
  one application read another application's per-connection HCI reason code.
