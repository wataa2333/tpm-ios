"""Capture actual simulator output for the mock-data SwiftUI preview."""
import json
from pathlib import Path
import subprocess
import time


def sim(*args):
    return subprocess.check_output(["xcrun", "simctl", *args], text=True)


devices = json.loads(sim("list", "devices", "available", "--json"))["devices"]
candidates = [d for runtime, rows in devices.items() if "iOS-27" in runtime
              for d in rows if d["name"].startswith("iPhone")]
if not candidates:
    raise SystemExit("No available iOS 27 iPhone simulator runtime; screenshots were not generated.")
device = next((d for d in candidates if d["name"] == "iPhone 17"), candidates[0])
udid = device["udid"]
print("Capturing on", device["name"], udid, flush=True)
if device["state"] != "Booted":
    sim("boot", udid)
sim("bootstatus", udid, "-b")
test_result = subprocess.run(["xcodebuild", "test", "-project", "ios/TPM.xcodeproj", "-scheme", "TPM",
                "-configuration", "Debug", "-destination", f"platform=iOS Simulator,id={udid}",
                "-derivedDataPath", "build/simulator", "CODE_SIGNING_ALLOWED=NO",
                "-resultBundlePath", "build/PreviewTests.xcresult"], check=False)
sim("status_bar", udid, "override", "--time", "9:41", "--dataNetwork", "wifi",
    "--wifiMode", "active", "--wifiBars", "3", "--batteryState", "charged", "--batteryLevel", "100")
sim("install", udid, "build/simulator/Build/Products/Debug-iphonesimulator/TPM.app")
output = Path("build/screenshots")
output.mkdir(parents=True, exist_ok=True)
subprocess.run(["xcrun", "xcresulttool", "export", "attachments",
                "--path", "build/PreviewTests.xcresult", "--output-path", str(output / "ui-tests")], check=True)
screens = [
    "faults", "new-fault", "processing", "erp-form", "workflow", "loading",
]
captures = [(f"{index:02d}-{screen}", "light", ["--screen", screen])
            for index, screen in enumerate(screens, 1)]
captures += [("07-faults-dark", "dark", ["--screen", "faults"])]
captures += [("11-factories", "light", ["--screen", "factories"])]
for index, screen in enumerate(["home", "workspace", "profile", "assets", "asset-detail", "stock", "stock-detail", "fault-detail", "working", "verifying", "finished", "repair-pool", "maintenance-pool", "precision-pool"], 12):
    captures.append((f"{index:02d}-{screen}", "light", ["--screen", screen]))
captures += [("26-processing-dark", "dark", ["--screen", "processing"]), ("27-new-fault-dark", "dark", ["--screen", "new-fault"])]
for index, screen in enumerate(["login", "asset-picker", "user-picker", "dept-picker", "group-picker", "division-picker", "project-picker", "material-picker", "fault-type-picker", "outbound-material-picker", "repair-result", "order-detail"], 40):
    captures.append((f"{index:02d}-{screen}", "light", ["--screen", screen]))
captures += [("52-home-dark", "dark", ["--screen", "home"]), ("53-workspace-dark", "dark", ["--screen", "workspace"]), ("54-erp-form-dark", "dark", ["--screen", "erp-form"])]
for index, screen in enumerate(["draft", "reported", "signed", "resolved", "sign-in", "return-order", "claim-order", "accept-order", "asset-repairs", "warehouse-receipts"], 55):
    captures.append((f"{index:02d}-{screen}", "light", ["--screen", screen]))
for name, appearance, arguments in captures:
    subprocess.run(["xcrun", "simctl", "terminate", udid, "com.personal.tpm"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sim("ui", udid, "appearance", appearance)
    sim("launch", udid, "com.personal.tpm", *arguments,
        "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN")
    time.sleep(10 if "erp-form" in arguments else 5)
    sim("io", udid, "screenshot", str(output / f"{name}.png"))
(output / "capture-info.json").write_text(json.dumps({
    "device": device["name"], "data": "fictional demo data",
    "capture": "actual iOS simulator; screens launched with DEBUG preview arguments; not an end-to-end flow test",
}, ensure_ascii=False, indent=2))
raise SystemExit(test_result.returncode)
