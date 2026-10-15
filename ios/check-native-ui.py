"""Run native UI regressions; do not run the screenshot capture pipeline."""
import json
import subprocess

devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"], text=True))["devices"]
available = [device for runtime, rows in devices.items() if "iOS-27" in runtime for device in rows if device["name"].startswith("iPhone")]
if not available:
    raise SystemExit("An iOS 27 simulator is required")
device = next((device for device in available if device["name"] == "iPhone 17"), available[0])
if device["state"] != "Booted":
    subprocess.run(["xcrun", "simctl", "boot", device["udid"]], check=True)
subprocess.run(["xcrun", "simctl", "bootstatus", device["udid"], "-b"], check=True)
result = subprocess.run(["xcodebuild", "test", "-project", "ios/TPM.xcodeproj", "-scheme", "TPM", "-configuration", "Debug", "-destination", f"platform=iOS Simulator,id={device['udid']}", "-derivedDataPath", "build/simulator", "CODE_SIGNING_ALLOWED=NO", "-resultBundlePath", "build/NativeUIChecks.xcresult"])
raise SystemExit(result.returncode)
