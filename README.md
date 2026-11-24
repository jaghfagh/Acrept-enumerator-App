# ACREPT Enumerator — Android app

The Android app JATA extension agents use to send commodity prices from each market in Jigawa's 27 LGAs to the ACREPT WebGIS. It works offline and keeps prices on the phone until there is network. If there is no data network, it can send them by SMS.

Built by DuneGIS Tech Limited. Version 1.0.

---

## 1. Build the APK (about 10 minutes)

1. Install **Android Studio** (Koala or newer). It brings the Android SDK and JDK 17.
2. Open Android Studio → **Open** → choose this `ACREPT-Enumerator` folder. Let Gradle sync; it downloads Gradle 8.7 and the Android plugin the first time.
3. Plug in an Android phone with USB debugging on, then press **Run ▶**. Or make an installable file:
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The APK appears in `app/build/outputs/apk/debug/app-debug.apk`.
4. For distribution to enumerators, use **Build → Generate Signed App Bundle / APK** with a JATA signing key. Keep the key safe: updates must be signed with the same key.

From the command line: `./gradlew assembleDebug` (needs `ANDROID_HOME` set to the SDK folder).

Requirements: Android 8.0 (API 26) or newer, GPS, camera optional.

## 2. How it is built

```
app/src/main/
├── assets/www/index.html      ← the whole enumerator app (HTML, CSS, JavaScript, offline)
├── java/.../MainActivity.kt   ← WebView host + native bridge
├── res/                       ← icon (ACREPT logo), colours, theme
└── AndroidManifest.xml        ← permissions: Internet, network state, location
```

- The app UI lives in one offline web file, served inside the app from `https://appassets.androidplatform.net/assets/www/index.html`. The same file runs as the browser prototype, so the design can be changed and tested without rebuilding Android code.
- `MainActivity.kt` adds what a browser cannot do well in the field, through `window.AndroidBridge`:

| Bridge call | What it does |
|---|---|
| `requestLocation()` | Gets a GPS fix (asks for permission first) and calls `window.acreptOnLocation(lat, lon, accuracy)` |
| `sendSms(number, body)` | Opens the phone's SMS app with the price code ready to send |
| `appVersion()` | Returns the app version |
| *(automatic)* network watch | Calls `window.acreptOnNetwork(true/false)` when data comes or goes, so the outbox sends itself |
| *(automatic)* photo chooser | The "Take photo" button opens the camera or gallery |
| *(automatic)* Back button | Goes back one screen; closes the app from Home |

## 3. Screens

1. **Sign in:** Enumerator ID (e.g. `JATA-DUT-014`) and PIN. English or Hausa.
2. **Home:** today's counts (prices, markets, waiting to send); markets in the enumerator's LGA first, then search across all 27 LGAs.
3. **Market:** market details; **Check my location** confirms the enumerator is within 500 m of the market point.
4. **Commodities:** 56 commodities by group, with a "Priority" tab of 12 staples; progress bar; search in English or Hausa.
5. **Price entry:**
   - The unit: trading unit, mudu or kg. Prices entered in mudu or kg are converted to the trading unit.
   - The price type: wholesale, retail or farm-gate.
   - The lowest, most common and highest price.
   - Today's supply level, the weight of the measure, and a photo of the measure.
   - A live check of the price against the market's 7-day median.
6. **Review and send:** today's prices for the market; **Send** (or queue if offline); **Send by SMS**.
7. **Outbox:** every reading with its status: waiting to send, sent, or held for review.
8. **Settings:** language, ACREPT server address, clear sent records, sign out.

## 4. Data quality rules in the app

- Lowest ≤ most common ≤ highest, or the price cannot be saved.
- A most-common price more than 25% away from the market's 7-day median shows a warning. If saved, the reading is flagged `price far from median` and the server holds it for supervisor review.
- A reading saved without a confirmed location within 500 m of the market is flagged `location not confirmed`.
- One reading per commodity, market and day: saving again replaces the unsent reading.

## 5. Server API (for the ACREPT backend team)

Set the server address in **Settings** (e.g. `https://acrept.example.ng`). `demo` keeps everything on the phone.

**POST** `{server}/api/v1/observations`

```json
{
  "enumerator": "JATA-DUT-014",
  "observations": [
    {
      "uid": "m3k9x2ab1c",
      "enumerator": "JATA-DUT-014",
      "market_id": "DUT01",
      "market": "Dutse central market",
      "lga": "Dutse",
      "code": "MZW",
      "unit": "100 kg bag",
      "kg_per_unit": 100,
      "entered_unit": "100 kg bag",
      "price_type": "W",
      "min": 31000, "modal": 32000, "max": 33500,
      "supply": "N",
      "measure_kg": 2.3,
      "photo": "data:image/jpeg;base64,...",
      "gps": { "lat": 11.7612, "lon": 9.3408, "acc": 8 },
      "captured_at": "2026-11-24T09:42:10.000Z",
      "flag": ""
    }
  ]
}
```

- `price_type`: `W` wholesale, `R` retail, `F` farm-gate. `supply`: `L` low, `N` normal, `H` high.
- Prices are naira per `unit` (the trading unit); the server converts to naira per kg with `kg_per_unit` or `measure_kg`.
- `uid` is unique per reading: the server should ignore a duplicate `uid` (safe re-sends).
- Reply `200` when stored. Any other reply leaves the readings in the outbox to retry.
- Planned next: `GET /api/v1/reference` (markets, commodities, 7-day medians) and sign-in with PIN on the server. In v1.0 these are bundled in the app.

## 6. SMS format (no data network)

```
ACR <ENUMERATOR> <MARKET> <DDMM> <CODE>:<lowest>/<most common>/<highest>:<W|R|F> ...
```

Example: `ACR DUT-014 DUT01 2411 MZW:31000/32000/33500:W MIL:35000/36000/37000:W`

The SMS gateway parses each `CODE:...` group as one reading. A message of up to 153 characters holds about 5 commodities; longer codes are sent as multi-part SMS.

### Commodity codes

| Group | Codes |
|---|---|
| Grains | MZW maize white · MZY maize yellow · SGW sorghum white · SGR sorghum red · MIL millet · PAD paddy rice · RCL local rice · RCI imported rice · WHT wheat |
| Legumes and oilseeds | CPW cowpea white · CPB cowpea brown · GNS groundnut shelled · GNU groundnut unshelled · SOY soybean · SES sesame · BAM bambara nut · GNO groundnut oil |
| Tubers and flours | GAR garri · YAM yam · SWP sweet potato · IRP Irish potato · CSF cassava flour |
| Vegetables | TOM tomato · ONI onion · TAT sweet pepper · ATA hot pepper · DPP dried pepper · OKF fresh okra · OKD dried okra · CAB cabbage · CAR carrot · GEG garden egg |
| Fruits | WML watermelon · MNG mango · ORG orange · DAT date fruit |
| Cash crops | GNG ginger · ZOB zobo · TGN tiger nut · GUM gum arabic · COT seed cotton · DAD daddawa |
| Livestock | BUL bull · COW cow · RAM ram · GOT goat · CML camel |
| Poultry and dairy | LCH local chicken · BRL broiler · EGG eggs · MLK nono |
| Fish | FFS fresh fish · DFS dried fish |
| Inputs | NPK · URE urea · BRN wheat bran |

Market codes are the first three letters of the LGA plus a number (Birnin Kudu is `BKD`, Biriniwa is `BRW`): `DUT01` Dutse central market, `HAD01` Hadejia central market, `MAI01` Maigatari border market, `TAU01` Gujungu market, and so on. Markets added in the census get `02`, `03`…

## 7. Before field roll-out

- [ ] Replace the demo PIN check with server sign-in (one ID and PIN per extension agent).
- [ ] Load the surveyed market coordinates from the census in place of the approximate points.
- [ ] Set the ACREPT server address and SMS shortcode for all phones.
- [ ] Have the Hausa labels reviewed by JATA staff (marked as draft in the app).
- [ ] Sign the release APK with a JATA key and install it through a device management tool or direct install.

## 8. Build the APK online with GitHub (no Android Studio needed)

1. Create a GitHub repository and upload the contents of this folder (keep the `.github` folder).
2. Open the repository's **Actions** tab. The workflow **Build ACREPT Enumerator APK** runs on every push to `main`, or press **Run workflow**.
3. After about 5–8 minutes, open the repository's **Releases** (right-hand side) and download `ACREPT-Enumerator.apk` from **ACREPT Enumerator (latest build)**.
4. Copy the APK to an Android phone and open it (allow "Install unknown apps" when asked).
