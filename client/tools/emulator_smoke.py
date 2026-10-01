"""ADB helpers for manually driven phone/TV smoke checks; no app data is cleared."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


class Device:
    def __init__(self, adb, serial, package):
        self.adb = str(adb)
        self.serial = serial
        self.package = package

    def command(self, *args, binary=False):
        return subprocess.check_output([self.adb, "-s", self.serial, *map(str, args)],
                                       text=not binary, encoding=None if binary else "utf-8", errors=None if binary else "replace")

    def key(self, *keys):
        self.command("shell", "input", "keyevent", *keys)

    def launch(self):
        self.command("shell", "am", "start", "-n", self.package + "/.MainActivity")

    def tree(self):
        self.command("shell", "uiautomator", "dump", "/sdcard/familytube-window.xml")
        return ET.fromstring(self.command("shell", "cat", "/sdcard/familytube-window.xml").strip())

    def texts(self):
        return [n.get("text") for n in self.tree().iter("node") if n.get("text")]

    def tap_text(self, text):
        root = self.tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        node = next(n for n in root.iter("node") if n.get("text") == text)
        while node.get("clickable") != "true" and node in parents:
            node = parents[node]
        points = list(map(int, re.findall(r"\d+", node.get("bounds"))))
        self.command("shell", "input", "tap", (points[0] + points[2]) // 2, (points[1] + points[3]) // 2)

    def fill(self, value, label=None):
        nodes = list(self.tree().iter("node"))
        edit = next(n for n in nodes if n.get("class") == "android.widget.EditText" and
                    (label is None or label in n.get("text", "")))
        points = list(map(int, re.findall(r"\d+", edit.get("bounds"))))
        self.command("shell", "input", "tap", (points[0] + points[2]) // 2, (points[1] + points[3]) // 2)
        self.key("KEYCODE_MOVE_END")
        self.key(*(["KEYCODE_DEL"] * (len(edit.get("text", "")) + 2)))
        if value:
            self.command("shell", "input", "text", value.replace(" ", "%s"))
        self.key("KEYCODE_BACK")  # Dismiss keyboard.

    def setup(self, url):
        self.launch()
        time.sleep(1)
        if "Server" in self.texts():
            if self.package.endswith(".tv"):
                self.focus("Server", key="KEYCODE_DPAD_UP")
                self.key("KEYCODE_DPAD_CENTER")
            else:
                self.tap_text("Server")
        if "Server address" not in self.texts():
            raise RuntimeError("Server setup screen is not visible; dismiss any emulator system prompt first")
        self.fill(url)
        if self.package.endswith(".tv"):
            self.key("KEYCODE_DPAD_DOWN")
            self.key("KEYCODE_DPAD_CENTER")
        else:
            self.tap_text("Save and connect")
        for _ in range(15):
            if "Checking…" not in self.texts():
                break
            time.sleep(1)
        if self.package.endswith(".tv"):
            self.key("KEYCODE_BACK")  # Return to catalog after the origin has been persisted.
        else:
            self.tap_text("Open library")

    def focus(self, text, key="KEYCODE_DPAD_DOWN", attempts=20):
        for _ in range(attempts):
            root = self.tree()
            for node in root.iter("node"):
                if node.get("focused") == "true" and any(child.get("text") == text for child in node.iter()):
                    return
            self.key(key)
        raise RuntimeError(f"Could not reach {text} with {key}")

    def screenshot(self, path):
        Path(path).write_bytes(self.command("exec-out", "screencap", "-p", binary=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--setup")
    parser.add_argument("--tap")
    parser.add_argument("--fill")
    parser.add_argument("--focus")
    parser.add_argument("--focus-key", default="KEYCODE_DPAD_DOWN")
    parser.add_argument("--key", nargs="+")
    parser.add_argument("--screenshot", type=Path)
    parser.add_argument("--launch", action="store_true")
    args = parser.parse_args()
    device = Device(args.adb, args.serial, args.package)
    if args.setup:
        device.setup(args.setup)
    if args.launch:
        device.launch()
    if args.tap:
        device.tap_text(args.tap)
    if args.fill is not None:
        device.fill(args.fill)
    if args.focus:
        device.focus(args.focus, key=args.focus_key)
    if args.key:
        device.key(*args.key)
    if args.screenshot:
        device.screenshot(args.screenshot)
    print(json.dumps(device.texts(), ensure_ascii=True))
