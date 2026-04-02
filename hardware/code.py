# Copyright 2026 Flakeforever
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import board
import time
import usb_hid
import terminalio
import displayio
import microcontroller
import digitalio
import random
from adafruit_display_text import label
from adafruit_hid.keyboard import Keyboard
from adafruit_hid.keyboard_layout_us import KeyboardLayoutUS
from adafruit_hid.keycode import Keycode
from adafruit_ble import BLERadio
from adafruit_ble.advertising.standard import ProvideServicesAdvertisement
from adafruit_ble.services.nordic import UARTService
from adafruit_ble.services.standard.hid import HIDService

# --- Metadata & Constants ---
FW_VERSION = "1.2.0-STABLE"
MODEL_NAME = "WaveShare ESP32-S3-GEEK"
TYPING_DELAY = 0.03
SCREEN_TIMEOUT = 600
COLOR_CYAN, COLOR_GREEN, COLOR_YELLOW, COLOR_RED = 0x00FFFF, 0x00FF00, 0xFFFF00, 0xFF0000

# --- Compatibility Patch: Hardware initialization window for cold boot ---
time.sleep(1.5)

# --- UI Management ---
class KPBDisplay:
    def __init__(self):
        self.display = board.DISPLAY
        self.is_sleeping = False
        self.curr_st, self.curr_ac = "BOOTING", "READY"
        self.group = displayio.Group()
        self.header = label.Label(terminalio.FONT, text="KeePass Bridge", color=0x00A2E8, scale=2,
                                  anchor_point=(0.5, 0.5), anchored_position=(120, 25))
        self.status = label.Label(terminalio.FONT, text=self.curr_st, color=COLOR_GREEN, scale=2,
                                  anchor_point=(0.5, 0.5), anchored_position=(120, 65))
        self.action = label.Label(terminalio.FONT, text=self.curr_ac, color=COLOR_YELLOW, scale=2,
                                  anchor_point=(0.5, 0.5), anchored_position=(120, 105))
        self.group.append(self.header); self.group.append(self.status); self.group.append(self.action)
        self.display.root_group = self.group
        self._refresh_brightness()

    def _refresh_brightness(self):
        if self.is_sleeping: self.display.brightness = 0
        elif self.curr_ac in ["BUSY...", "UNDOING", "REBOOT"]: self.display.brightness = 0.35
        elif self.curr_st in ["SCAN ME", "OFFLINE"]: self.display.brightness = 0.05
        else: self.display.brightness = 0.15

    def wake(self):
        if self.is_sleeping:
            self.is_sleeping = False
            self._refresh_brightness()

    def sleep(self):
        if not self.is_sleeping:
            self.is_sleeping = True
            self._refresh_brightness()

    def update(self, st=None, ac=None, ac_col=None):
        self.wake()
        if st: self.curr_st = st; self.status.text = st
        if ac: self.curr_ac = ac; self.action.text = ac
        if ac_col: self.action.color = ac_col
        self._refresh_brightness()

# --- HID Management with Human Simulation ---
class KPBKeyboard:
    def __init__(self):
        self.kbd = Keyboard(usb_hid.devices)
        self.layout = KeyboardLayoutUS(self.kbd)
        self.last_action = {"type": None, "value": 0}

    def type_text(self, text):
        for char in text:
            self.layout.write(char)
            # Randomized typing delay to mimic human behavior and bypass heuristic analysis
            time.sleep(TYPING_DELAY + random.uniform(0, 0.02))
        self.last_action = {"type": "TXT", "value": len(text)}

    def send_key(self, key_code, act_type):
        self.kbd.send(key_code)
        self.last_action = {"type": act_type, "value": 0}

    def undo(self):
        if not self.last_action["type"]: return False
        if self.last_action["type"] == "TXT":
            for _ in range(self.last_action["value"]):
                self.kbd.send(Keycode.BACKSPACE); time.sleep(TYPING_DELAY)
        elif self.last_action["type"] == "TAB":
            self.kbd.press(Keycode.SHIFT, Keycode.TAB); self.kbd.release_all()
        self.last_action = {"type": None, "value": 0}
        return True

# --- Protocol Handler ---
class ProtocolHandler:
    def __init__(self, kbd_mgr, ui_mgr, uart_svc):
        self.kbd, self.ui, self.uart = kbd_mgr, ui_mgr, uart_svc

    def handle(self, raw_str, is_paired):
        if ":" not in raw_str: return
        prefix, content = raw_str.split(":", 1)
        if prefix == "GET": self._handle_get(content, is_paired)
        elif prefix == "CMD": self._handle_cmd(content)
        elif prefix == "TXT": self._handle_txt(content)

    def _handle_get(self, content, is_paired):
        if content == "INFO":
            status = "SECURE" if is_paired else "GUEST"
            msg = f"INFO:{MODEL_NAME}|{FW_VERSION}|{status}\n"
            self.uart.write(msg.encode("utf-8"))

    def _handle_cmd(self, content):
        self.ui.update(ac="BUSY...", ac_col=COLOR_CYAN)
        if content == "UNDO":
            self.kbd.undo()
        elif content == "ENTER": self.kbd.send_key(Keycode.ENTER, "ENTER")
        elif content == "TAB": self.kbd.send_key(Keycode.TAB, "TAB")
        elif content == "LOCK":
            self.kbd.kbd.press(Keycode.GUI, Keycode.L); self.kbd.kbd.release_all()
        self.uart.write(b"OK:DONE\n")
        time.sleep(0.4); self.ui.update(ac="READY", ac_col=COLOR_GREEN)

    def _handle_txt(self, content):
        self.ui.update(ac="BUSY...", ac_col=COLOR_CYAN)
        self.kbd.type_text(content)
        self.uart.write(b"OK:DONE\n"); time.sleep(0.2)
        self.ui.update(ac="READY", ac_col=COLOR_GREEN)

# --- App Initialization ---
ui = KPBDisplay()
kpb_kbd = KPBKeyboard()
ble = BLERadio()

# Physical button initialization (used for long-press reboot into dev mode)
boot_btn = digitalio.DigitalInOut(board.BUTTON)
boot_btn.direction = digitalio.Direction.INPUT
boot_btn.pull = digitalio.Pull.UP

uart = UARTService()
hid = HIDService()
handler = ProtocolHandler(kpb_kbd, ui, uart)

ble.name = "KPB-Bridge"
adv = ProvideServicesAdvertisement(hid, uart)
adv.complete_name = ble.name

last_interaction = time.monotonic()

while True:
    # --- 1. Advertising Loop ---
    if not ble.connected:
        try:
            ble.start_advertising(adv)
        except Exception:
            pass
            
        ui.update(st="SCAN ME", ac="IDLE", ac_col=COLOR_YELLOW)
        while not ble.connected:
            # Monitor BOOT button for forced reboot
            if not boot_btn.value:
                press_start = time.monotonic()
                while not boot_btn.value:
                    if time.monotonic() - press_start > 3.0:
                        ui.update(st="REBOOT", ac="DEV MODE", ac_col=COLOR_RED)
                        time.sleep(1)
                        microcontroller.reset()
                    time.sleep(0.1)
            
            if (time.monotonic() - last_interaction) > SCREEN_TIMEOUT: ui.sleep()
            time.sleep(0.1)
        
        try:
            ble.stop_advertising()
        except:
            pass
            
        ui.wake()
        last_interaction = time.monotonic()

    # --- 2. Connected Session ---
    already_secure = False
    while ble.connected:
        try:
            # Continuously monitor BOOT button
            if not boot_btn.value:
                press_start = time.monotonic()
                while not boot_btn.value:
                    if time.monotonic() - press_start > 3.0:
                        ui.update(st="REBOOT", ac="DEV MODE", ac_col=COLOR_RED)
                        time.sleep(1)
                        microcontroller.reset()
                    time.sleep(0.1)

            is_paired = any(conn.paired for conn in ble.connections)
            if is_paired and not already_secure:
                ui.update(st="SECURE", ac="READY", ac_col=COLOR_GREEN)
                already_secure = True
                last_interaction = time.monotonic()
            
            if uart.in_waiting:
                last_interaction = time.monotonic()
                try:
                    data = uart.read(uart.in_waiting).decode("utf-8").strip()
                    if data: handler.handle(data, is_paired)
                except Exception: 
                    pass

        except Exception as e:
            # Catch exceptions caused by abrupt disconnections to prevent script crash
            print("BLE session error:", e)
            break

        if (time.monotonic() - last_interaction) > SCREEN_TIMEOUT: ui.sleep()
        time.sleep(0.01)

    ui.update(st="OFFLINE", ac="DISCON.", ac_col=COLOR_RED)
    last_interaction = time.monotonic()
    time.sleep(1)