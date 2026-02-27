# 🦞 ClawPhone — AI Remote Phone Control

Android aplikacija, ki omogoča AI agentom daljinsko upravljanje Android telefona prek WebSocket strežnika.

## Funkcije

- **Tap/Swipe/Scroll** — dotiki, potegi, pomikanje po zaslonu
- **Type** — vnos besedila v aktivno polje
- **Screenshot** — zajem zaslona kot base64 JPEG
- **Back/Home/Recents** — navigacijski gumbi
- **WebSocket strežnik** na portu 8765 z avtentikacijo

---

## 📦 Kako zgraditi APK

### Možnost A: Android Studio

1. Odpri Android Studio
2. **File → Open** → izberi mapo `clawphone/`
3. Počakaj, da se Gradle sinhronizira
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK se nahaja v `app/build/outputs/apk/debug/app-debug.apk`

### Možnost B: Ukazna vrstica

```bash
cd clawphone/
chmod +x gradlew   # če je potrebno
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ Potrebuješ Android SDK in JDK 17+.

---

## 📱 Kako namestiti na telefon

### Prek USB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Prek telefona:
1. Prenesi APK datoteko na telefon
2. Odpri datoteko in dovoli namestitev iz neznanih virov
3. Namesti aplikacijo

---

## ⚙️ Potrebna dovoljenja in nastavitve

### 1. Dovoljenje za obvestila (Android 13+)
- Ob prvem zagonu bo aplikacija prosila za dovoljenje
- Dovoli, da bo prikazana obvestilna vrstica med delovanjem

### 2. Accessibility Service (Storitev za dostopnost)
To je **najpomembnejši korak**!

1. Odpri **Nastavitve → Dostopnost** (Settings → Accessibility)
   - Ali klikni gumb **"Open Accessibility Settings"** v aplikaciji
2. Poišči **ClawPhone**
3. **Vklopi** storitev
4. Potrdi opozorilo (aplikacija potrebuje ta dostop za izvajanje dotikov in potegov)

> V aplikaciji se bo status spremenil v "🟢 Accessibility Service: ON"

### 3. Dovoljenje za zajem zaslona (Screen Capture)
1. V aplikaciji klikni **"Grant Screen Capture"**
2. Prikaže se sistemski dialog — klikni **"Začni zdaj"** (Start now)
3. Status se spremeni v "🟡 Screen Capture: GRANTED"

### 4. Zaženi strežnik
1. Nastavi **Auth Token** (privzeto: `changeme`) — spremeni v nekaj varnega!
2. Nastavi **Port** (privzeto: `8765`)
3. Klikni **"Start Server"**
4. Status se spremeni v "🟢 WebSocket Server: ON"
5. Prikaže se IP naslov za povezavo

---

## 🔌 Kako se povezati iz agenta

### WebSocket URL
```
ws://<IP_TELEFONA>:8765
```

IP naslov se prikaže v aplikaciji. Telefon in odjemalec morata biti na **istem WiFi omrežju**.

### Avtentikacija
Pošlji token kot:
- HTTP header: `Authorization: Bearer <token>`
- Ali kot query parameter: `ws://<IP>:8765/?token=<token>`

---

## 🐍 Primer uporabe iz Pythona

```python
import asyncio
import json
import websockets

async def main():
    uri = "ws://192.168.1.100:8765/?token=changeme"
    
    async with websockets.connect(uri) as ws:
        # Počakaj na potrditev avtentikacije
        auth_response = await ws.recv()
        print("Auth:", auth_response)
        
        # Tap na koordinate
        await ws.send(json.dumps({"action": "tap", "x": 500, "y": 1000}))
        print(await ws.recv())
        
        # Screenshot
        await ws.send(json.dumps({"action": "screenshot"}))
        response = json.loads(await ws.recv())
        if response["status"] == "ok":
            import base64
            with open("screenshot.jpg", "wb") as f:
                f.write(base64.b64decode(response["image"]))
            print("Screenshot shranjen!")
        
        # Swipe navzgor
        await ws.send(json.dumps({
            "action": "swipe",
            "x1": 500, "y1": 1500,
            "x2": 500, "y2": 500,
            "duration": 300
        }))
        print(await ws.recv())
        
        # Vpis besedila
        await ws.send(json.dumps({"action": "type", "text": "Pozdravljen svet!"}))
        print(await ws.recv())
        
        # Navigacija
        await ws.send(json.dumps({"action": "back"}))
        print(await ws.recv())
        
        await ws.send(json.dumps({"action": "home"}))
        print(await ws.recv())
        
        # Scroll
        await ws.send(json.dumps({"action": "scroll", "direction": "down"}))
        print(await ws.recv())

asyncio.run(main())
```

### Namestitev Python knjižnice:
```bash
pip install websockets
```

---

## 🔧 Primer z websocat (CLI)

```bash
# Namesti websocat
# brew install websocat  (macOS)
# cargo install websocat  (Rust)

# Poveži se
echo '{"action":"screenshot"}' | websocat "ws://192.168.1.100:8765/?token=changeme"
```

---

## 📋 WebSocket protokol — vsi ukazi

| Ukaz | Parametri | Opis |
|------|-----------|------|
| `tap` | `x`, `y` | Dotik na koordinate |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `duration` | Poteg (duration v ms) |
| `type` | `text` | Vnos besedila v fokusirano polje |
| `screenshot` | `quality` (neobvezno, privzeto 50) | Zajem zaslona |
| `scroll` | `direction` (up/down/left/right) | Pomikanje po zaslonu |
| `back` | — | Gumb nazaj |
| `home` | — | Gumb domov |
| `recents` | — | Nedavne aplikacije |
| `ping` | — | Preverjanje povezave |

### Odgovori
- Uspeh: `{"status": "ok", ...}`
- Napaka: `{"status": "error", "message": "opis napake"}`
- Screenshot: `{"status": "ok", "image": "base64..."}`

---

## 🔒 Varnost

- **Spremeni privzeti token!** `changeme` je samo za testiranje.
- Uporabljaj samo na **zasebnem WiFi omrežju**.
- Aplikacija **ne šifrira** prometa (ni TLS/SSL) — za produkcijo uporabi VPN ali SSH tunel.
- Accessibility Service ima **poln dostop** do zaslona — uporabljaj odgovorno.

---

## 🐛 Odpravljanje težav

| Problem | Rešitev |
|---------|---------|
| "Accessibility service not running" | Preveri, da je storitev vklopljena v Nastavitve → Dostopnost |
| "Screen capture not initialized" | Klikni "Grant Screen Capture" in potrdi dialog |
| Ne morem se povezati | Preveri, da sta naprava in odjemalec na istem omrežju. Preveri IP in port. |
| Tap/swipe ne deluje | Preveri, da je Accessibility Service res vklopljen (nekateri telefoni ga samodejno izklopijo) |
| Screenshot je črn | Nekatere aplikacije (DRM) blokirajo zajem zaslona |
