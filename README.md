# Edzés

Személyes, offline-first Android edzésapp. Nincs saját szerver: minden adat a telefonon van.

- **Programok Claude-ból**: a „Claude prompt másolása” gombbal kapott prompttal kérj edzéstervet, a JSON választ
  illeszd be / nyisd meg fájlból / oszd meg a Claude appból az Edzés appnak. Egy lépésben beütemezi az egész ciklust,
  és bármikor újraindítható.
- **Naptár**: napokra ütemezett edzések, kész / kihagyva jelölés, áthelyezés.
- **Lejátszó**: súlyzós sorozatok naplózása pihenő-visszaszámlálóval, HIIT/Tabata/időzített gyakorlatok,
  futó-/kerékpáros szakaszok (idő vagy GPS-táv alapján) magyar hangbemondással, zárolt képernyő mellett is.
  Szabad edzés és gyors intervall időzítő terv nélkül.
- **Health Connect**: az órával (Fitbit / Google Health) mért edzések, pulzus, táv, útvonal átvétele; automatikus
  párosítás a napi tervvel.
- **Strava**: kétirányú szinkron duplikáció-szűréssel. A konditermi edzés leírásába a gyakorlatok és súlyok is felkerülnek.
- **Mentés / visszaállítás**: teljes adatbázis egy JSON fájlban (Claude-nak is feltölthető elemzésre).

## Telepítés

1. GitHub → **Actions** → legutóbbi „APK build” futás → **edzes-apk** letöltése (a `main` ágon a **Releases → latest**
   alatt közvetlenül is ott az `edzes.apk`).
2. Telepítsd a telefonra (ismeretlen forrás engedélyezése kell).

### Állandó aláíró kulcs (egyszeri beállítás, ajánlott)

Enélkül minden build más kulccsal készül, ezért frissítéskor előbb törölni kellene az appot (az adatokkal együtt).
Saját gépen generálj egy kulcsot:

```bash
keytool -genkeypair -keystore edzes.jks -alias edzes -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Edzes"
```

```bash
base64 -w0 edzes.jks
```

Majd a GitHub repóban: **Settings → Secrets and variables → Actions** alatt vedd fel a `KEYSTORE_BASE64` (a base64 kimenet)
és a `KEYSTORE_PASSWORD` (a megadott jelszó) titkokat. A `edzes.jks` fájlt őrizd meg.

## Beállítás (az appban: Beállítások)

**Health Connect** – „Engedélyek megadása”. A Google Health (Fitbit) appban is legyen bekapcsolva a Health Connect
szinkron. Az első útvonalas edzés feltöltésekor a Health Connect külön rákérdez az útvonalra: válaszd a
„Minden útvonal engedélyezése” lehetőséget, onnantól automatikus. Csak az engedélyezés előtti 30 nap edzései látszanak.

**Strava**
1. <https://www.strava.com/settings/api> → saját API alkalmazás létrehozása (név, weboldal tetszőleges).
2. *Authorization Callback Domain*: `localhost`
3. A *Client ID* és *Client Secret* beírása az appba → „Összekapcsolás”.
4. A Google Health → Strava közvetlen szinkront kapcsold ki, különben dupla edzések keletkezhetnek.

Automatikusan csak az összekapcsolás utáni edzések mennek fel; a régebbiek és a séták kézzel (vagy kapcsolóval).

## Elemzés Claude-ban

Kapcsold be a Strava connectort a claude.ai beállításaiban – Claude onnan minden edzést lát (a súlyzós edzések
részleteit a leírásból). Alternatíva: Beállítások → „Mentés fájlba”, és a JSON feltöltése a beszélgetésbe.

## Program formátum

A teljes leírás és példa a prompt szövegében van: [`Program.kt`](app/src/main/java/hu/edzes/model/Program.kt) (`CLAUDE_PROMPT`).
Blokktípusok: `sets`, `timed`, `interval`, `segment`, `repeat`.

## Fejlesztés

```bash
./gradlew testDebugUnitTest assembleRelease
```

A tesztek JVM-en futnak (Robolectric); a füstteszt képernyőképei: `app/build/outputs/roborazzi/`.
