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
import aesgcm

# --- File Logger ---
LOG_PATH = "/ble.log"
DEBUG = False  # True: per-message RX/TX traces; False: lifecycle + errors only
def klog(msg):
    try:
        with open(LOG_PATH, "a") as f:
            f.write(msg + "\n")
    except Exception:
        pass

def tlog(msg):
    # Trace-level log: only written when DEBUG is on.
    if DEBUG:
        klog(msg)
from adafruit_display_text import label
from adafruit_hid.keyboard import Keyboard
from adafruit_hid.keyboard_layout_us import KeyboardLayoutUS
from adafruit_hid.keycode import Keycode
from adafruit_ble import BLERadio
from adafruit_ble.advertising.standard import ProvideServicesAdvertisement
from adafruit_ble.services.nordic import UARTService

# --- Metadata & Constants ---
FW_VERSION = "1.5.0-STABLE"
MODEL_NAME = "WaveShare ESP32-S3-GEEK"
TYPING_DELAY = 0.03
SCREEN_TIMEOUT = 600
COLOR_CYAN, COLOR_GREEN, COLOR_YELLOW, COLOR_RED = 0x00FFFF, 0x00FF00, 0xFFFF00, 0xFF0000

# --- Crypto / PSK Management ---
PSK_PATH = "/psk.bin"

def _rand12():
    return bytes(random.getrandbits(8) for _ in range(12))

def load_psk():
    try:
        with open(PSK_PATH, "rb") as f:
            k = f.read()
        return k if len(k) == 16 else None
    except OSError:
        return None

def save_psk(key):
    with open(PSK_PATH, "wb") as f:
        f.write(key)

def wipe_psk():
    try:
        import os
        os.remove(PSK_PATH)
    except OSError:
        pass

def enc_send(key, payload):
    nonce = _rand12()
    ct, tag = aesgcm.gcm_encrypt(key, nonce, payload)
    return b"ENC:" + aesgcm.b64_encode(nonce + ct + tag) + b"\n"

def dec_recv(key, content):
    try:
        data = aesgcm.b64_decode(content.encode("utf-8").strip())
        tlog("b64 in=" + str(len(content)) + " out=" + str(len(data)))
    except Exception:
        klog("b64 EXC")
        return None
    if len(data) < 12 + 16:
        klog("too short=" + str(len(data)))
        return None
    nonce, ct, tag = data[:12], data[12:-16], data[-16:]
    result = aesgcm.gcm_decrypt(key, nonce, ct, tag)
    if result and result[1]:
        return result[0].decode("utf-8")
    klog("GCM AUTH FAIL")
    return None

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
        self.psk = load_psk()
        # Set once the app sends any command in this session.
        # Used to stop the connect re-announce loop.
        self.app_responded = False
        # Line buffer: BLE notifications arrive fragmented (20-byte chunks),
        # so a single uart.read() can split a command across reads. Buffer
        # bytes and only dispatch complete '\n'-terminated lines.
        self.rx = ""
        self.rx_time = 0.0

    def flush_stale(self):
        # A partial line pending >1s is treated as complete. Covers senders
        # that omit the trailing newline (e.g. KEY:<hex> with no \n).
        if self.rx and (time.monotonic() - self.rx_time) >= 1.0:
            line = self.rx.strip()
            self.rx = ""
            if line:
                self.handle(line)

    def feed(self, data_str):
        # Append and process whole lines; keep the incomplete tail buffered.
        self.rx += data_str
        if len(self.rx) > 512:
            self.rx = self.rx[-512:]  # runaway guard against garbage streams
        while "\n" in self.rx:
            line, self.rx = self.rx.split("\n", 1)
            line = line.strip()
            if line:
                self.handle(line)
        if self.rx:
            self.rx_time = time.monotonic()

    def handle(self, raw_str):
        tlog("RX:" + raw_str[:80])
        if ":" not in raw_str: return
        prefix, content = raw_str.split(":", 1)
        if prefix == "ENC":
            if self.psk is None:
                klog("RX:ENC no PSK - keep announcing INFO")
                return  # no PSK: cannot decrypt; keep the connect announcement alive
            # so the app can still learn our state and push its KEY:
            pt = dec_recv(self.psk, content)
            if pt is None:
                klog("RX:ENC FAIL len=" + str(len(content)))
                return  # bad tag / wrong key: keep announcing so the app self-heals
            self.app_responded = True
            tlog("RX:ENC OK pt=" + pt[:40])
            self._dispatch(pt)
        elif prefix == "KEY":
            self.app_responded = True
            self._handle_key(content)
        elif prefix == "GET":
            self.app_responded = True
            self._handle_get(content)
        elif prefix in ("CMD", "TXT"):
            # plaintext actions only allowed in factory (no PSK)
            if self.psk is None:
                self.app_responded = True
                self._dispatch(prefix + ":" + content)
            # paired state: ignore plaintext actions but KEEP announcing,
            # so an out-of-sync app receives INFO and re-pushes its KEY.

    def _dispatch(self, payload):
        if ":" not in payload: return
        prefix, content = payload.split(":", 1)
        if prefix == "CMD":
            self._handle_cmd(content)
        elif prefix == "TXT":
            self._handle_txt(content)
        elif prefix == "GET":
            self._handle_get(content)
        elif prefix == "KEY":
            # An app may deliver KEY: inside ENC: once it holds a PSK. Without
            # this branch the decrypted KEY is silently dropped, no ACK is
            # sent, and the app's pairing write times out (observed in field).
            self._handle_key(content)

    def _reply(self, plain):
        tlog("TX:" + plain[:60])
        if self.psk is None:
            self.uart.write(plain.encode("utf-8") + b"\n")
        else:
            resp = enc_send(self.psk, plain)
            tlog("TX:ENC len=" + str(len(resp)))
            self.uart.write(resp)

    def _handle_key(self, content):
        # Accept in BOTH states: factory (no key) and paired (has key).
        # The app is the master: it sends the key it wants the device to use.
        # In paired state the stored key is REPLACED (self-heal drift).
        try:
            k = bytes.fromhex(content.strip())
            if len(k) != 16:
                return
        except ValueError:
            return
        had_key = self.psk is not None
        self.psk = k
        save_psk(k)
        # Reflect the new key state on the LCD immediately: without this the
        # screen stays "ONLINE" after pairing and the user cannot tell the
        # link is encrypted.
        self.ui.update(st="ENCRYPTED", ac="KEY SET", ac_col=COLOR_CYAN)
        klog("KEY " + ("REPLACED" if had_key else "STORED") + " -> state ENCRYPTED")
        # Reply in plaintext: the app may not have this key stored yet,
        # so an encrypted ACK would be undecryptable and sync would time out.
        self.uart.write(b"OK:KEYSYNCED\n" if had_key else b"OK:KEYSTORED\n")

    def _handle_get(self, content):
        if content.strip() == "INFO":
            state = "ENCRYPTED" if self.psk is not None else "FACTORY"
            self._reply(f"INFO:{MODEL_NAME}|{FW_VERSION}|{state}")

    def _handle_cmd(self, content):
        content = content.strip()
        self.ui.update(ac="BUSY...", ac_col=COLOR_CYAN)
        if content == "UNDO":
            self.kbd.undo()
        elif content == "ENTER":
            self.kbd.send_key(Keycode.ENTER, "ENTER")
        elif content == "TAB":
            self.kbd.send_key(Keycode.TAB, "TAB")
        elif content == "LOCK":
            self.kbd.kbd.press(Keycode.GUI, Keycode.L); self.kbd.kbd.release_all()
        self._reply("OK:DONE")
        time.sleep(0.4); self.ui.update(ac="READY", ac_col=COLOR_GREEN)

    def _handle_txt(self, content):
        content = content.strip()
        self.ui.update(ac="BUSY...", ac_col=COLOR_CYAN)
        self.kbd.type_text(content)
        self._reply("OK:DONE"); time.sleep(0.2)
        self.ui.update(ac="READY", ac_col=COLOR_GREEN)

# --- App Initialization ---
ui = KPBDisplay()
kpb_kbd = KPBKeyboard()
ble = BLERadio()

# Physical button initialization (used for long-press reboot into dev mode)
boot_btn = digitalio.DigitalInOut(board.BUTTON)
boot_btn.direction = digitalio.Direction.INPUT
boot_btn.pull = digitalio.Pull.UP

# Long-press button state: 3s shows warning, 10s wipes PSK + reboots
_hold = {"active": False, "start": 0.0}

def check_button():
    if boot_btn.value:
        if _hold["active"]:
            _hold["active"] = False
            ui.update(ac="READY", ac_col=COLOR_GREEN)
        return None
    now = time.monotonic()
    if not _hold["active"]:
        _hold["active"] = True
        _hold["start"] = now
        return None
    if now - _hold["start"] >= 10:
        _hold["active"] = False
        return "wipe"
    if now - _hold["start"] >= 3:
        ui.update(st="HOLD", ac="10s TO WIPE", ac_col=COLOR_RED)
    return None

uart = UARTService()
handler = ProtocolHandler(kpb_kbd, ui, uart)
klog("=== BOOT fw=" + FW_VERSION + " psk=" + str(handler.psk is not None) + " ===")

ble.name = "KPB"
adv = ProvideServicesAdvertisement(uart)
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
        adv_tick = 0
        while not ble.connected:
            # Long-press button: 3s warns, 10s wipes PSK + reboots
            act = check_button()
            if act == "wipe":
                ui.update(st="WIPED", ac="REBOOT", ac_col=COLOR_RED)
                time.sleep(1)
                try:
                    import os
                    os.remove(PSK_PATH)
                except Exception:
                    pass
                microcontroller.reset()

            # Re-advertise periodically so a one-shot start failure recovers
            adv_tick += 1
            if adv_tick >= 10:
                adv_tick = 0
                try:
                    ble.start_advertising(adv)
                except Exception:
                    pass
            if (time.monotonic() - last_interaction) > SCREEN_TIMEOUT: ui.sleep()
            time.sleep(0.1)
        
        try:
            ble.stop_advertising()
        except:
            pass
            
        ui.wake()
        if handler.psk is not None:
            ui.update(st="ENCRYPTED", ac="READY", ac_col=COLOR_CYAN)
        else:
            ui.update(st="ONLINE", ac="READY", ac_col=COLOR_GREEN)
        last_interaction = time.monotonic()

    # --- 2. Connected Session ---
    already_secure = False
    klog("=== CONNECTED psk=" + str(handler.psk is not None) + " ===")
    handler.rx = ""  # drop any stale partial line from a previous session
    # Announce local key state in plaintext (no secrets in this line).
    # Re-send every 2s until the app sends ANY command: the app notification
    # subscription can lag behind our connected event, so a one-shot
    # announcement would be lost and both sides would sit waiting forever.
    handler.app_responded = False
    _ann_last = 0.0
    while ble.connected and not handler.app_responded:
        now = time.monotonic()
        if now - _ann_last >= 2.0:
            _ann_last = now
            _ann_state = "ENCRYPTED" if handler.psk is not None else "FACTORY"
            handler.uart.write(f"INFO:{MODEL_NAME}|{FW_VERSION}|{_ann_state}\n".encode("utf-8"))
            tlog("TX:ANNOUNCE " + _ann_state)
        # MUST drain UART here: the app's GET:INFO / KEY: writes land in the
        # RX buffer while we are announcing. Without this read the KEY: sits
        # unread, app_responded never flips, and both sides wait forever
        # (app buttons stuck disabled awaiting the KEY ACK).
        if handler.uart.in_waiting:
            last_interaction = time.monotonic()
            try:
                _chunk = handler.uart.read(handler.uart.in_waiting).decode("utf-8")
                tlog("ANN RX: " + _chunk[:60])
                if _chunk:
                    handler.feed(_chunk)
            except Exception as e:
                klog("ANN RX EXC: " + str(e))
        handler.flush_stale()
        time.sleep(0.1)
    while ble.connected:
        try:
            # Long-press button: 3s warns, 10s wipes PSK + reboots
            act = check_button()
            if act == "wipe":
                ui.update(st="WIPED", ac="REBOOT", ac_col=COLOR_RED)
                time.sleep(1)
                try:
                    import os
                    os.remove(PSK_PATH)
                except Exception:
                    pass
                microcontroller.reset()

            is_paired = any(conn.paired for conn in ble.connections)
            if is_paired and not already_secure:
                ui.update(st="SECURE", ac="READY", ac_col=COLOR_GREEN)
                already_secure = True
                last_interaction = time.monotonic()
            
            if uart.in_waiting:
                last_interaction = time.monotonic()
                try:
                    chunk = uart.read(uart.in_waiting).decode("utf-8")
                    tlog("UART: " + chunk[:100])
                    if chunk: handler.feed(chunk)
                except Exception as e:
                    klog("HANDLE EXC: " + str(e))
                    pass
                    if False: pass
                except Exception: 
                    pass
            # Flush a newline-less tail (e.g. KEY:<hex> without \n) once it
            # has been idle for 1s, so senders that omit the terminator still
            # get processed instead of the line sitting in the buffer forever.
            handler.flush_stale()

        except Exception as e:
            # Catch exceptions caused by abrupt disconnections to prevent script crash
            print("BLE session error:", e)
            break

        if (time.monotonic() - last_interaction) > SCREEN_TIMEOUT: ui.sleep()
        time.sleep(0.01)

    ui.update(st="OFFLINE", ac="DISCON.", ac_col=COLOR_RED)
    klog("=== DISCONNECTED ===")
    last_interaction = time.monotonic()
    time.sleep(1)
