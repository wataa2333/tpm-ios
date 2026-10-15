"""Run native UI regressions; do not run the screenshot capture pipeline."""
import json
import os
import subprocess

devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"], text=True))["devices"]
available = [device for runtime, rows in devices.items() if "iOS-27" in runtime for device in rows if device["name"].startswith("iPhone")]
if not available:
    raise SystemExit("An iOS 27 simulator is required")
device = next((device for device in available if device["name"] == "iPhone 17"), available[0])
if device["state"] != "Booted":
    subprocess.run(["xcrun", "simctl", "boot", device["udid"]], check=True)
subprocess.run(["xcrun", "simctl", "bootstatus", device["udid"], "-b"], check=True)
arguments = ["xcodebuild", "test", "-project", "ios/TPM.xcodeproj", "-scheme", "TPM", "-configuration", "Debug", "-destination", f"platform=iOS Simulator,id={device['udid']}", "-derivedDataPath", "build/simulator", "CODE_SIGNING_ALLOWED=NO", "-resultBundlePath", "build/NativeUIChecks.xcresult"]
if os.environ.get("TPM_TEST_SCOPE") == "navigation":
    arguments += ["-only-testing:TPMUITests/PreviewTests/testHomeFirstAndDashboardFactoryEntry", "-only-testing:TPMUITests/PreviewTests/testNewActionIsSeparateFromTabs", "-only-testing:TPMUITests/PreviewTests/testCompactSpacingAndRepeatedExpansion"]
result = subprocess.run(arguments)
raise SystemExit(result.returncode)
