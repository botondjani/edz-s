# Edzés

Személyes, offline-first Android edzésapp. Nincs saját szerver: minden adat a telefonon van.

- **Programok Claude-ból**: a „Claude prompt másolása” gombbal kapott prompttal kérj edzéstervet, a JSON választ
  illeszd be / nyisd meg fájlból / oszd meg a Claude appból az Edzés appnak. Egy lépésben beütemezi az egész ciklust,
  és bármikor újraindítható.
- **Naptár**: napokra ütemezett edzések, kész / kihagyva jelölés, áthelyezés.
- **Edzés indítása** (Ma fül): Futás / Kerékpár / Séta élő idővel, távval, tempóval és pulzussal, GPS-szel rögzítve;
  Súlyzós edzés sorozatnaplóval és pihenő-visszaszámlálóval; Intervall-szerkesztő (bemelegítés, N × munka idő vagy táv
  szerint + pihenő, levezetés), sablonként menthető. Magyar hangbemondás, zárolt képernyő mellett is; kilométerenként
  átlagtempó, intervallnál részidők.
- **Élő pulzus**: Bluetooth LE pulzusszolgáltatásból (mellkaspánt, vagy pulzust megosztó karkötő). Fitbit Airnél a
  Google Health appban kell bekapcsolni a pulzus megosztását („Share heart rate”, mindig látható).
- **Health Connect**: az órával (Fitbit / Google Health) mért edzések, pulzus, táv, útvonal átvétele; automatikus
  párosítás a napi tervvel. Ha ugyanazt az edzést az app is mérte, a kettőt összevonja. Az úszást csak az óra méri.
- **Strava**: kétirányú szinkron duplikáció-szűréssel. A konditermi edzés leírásába a gyakorlatok és súlyok is felkerülnek.
- **Pulzuszónák**: a Beállításokban megadott max pulzusból; edzés közben látszik az aktuális zóna, és ha a szakasznak
  célzónája van (pl. `"target": "Z2"`), az app szól, ha tartósan kilépsz belőle.
- **Edzés részletei**: útvonal, tempó- és pulzusgörbe, zónaidők, részidők / sorozatok.
- **Statisztika** (Előzmények → Statisztika): heti edzésidő sportonként, heti zónaidők, edzettség–fáradtság görbe.
- **Elemzés Claude-dal**: egy gombbal elemzésre kész edzésnaplót (heti összesítő, edzések, terv) oszt meg a Claude appnak
  vagy másol a vágólapra.
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
Az appban mért edzés feltöltésével az app legfeljebb fél órát vár, hogy az óra pulzusadata megérkezzen a Health Connectbe
(élő Bluetooth-pulzussal azonnal megy). Az Előzményekben kézzel is feltölthető.

## Elemzés Claude-ban

Előzmények → Statisztika → „Megosztás Claude-dal” (vagy „Másolás”, és beillesztés egy beszélgetésbe). A választ –
ha új edzésterv – a szokásos módon, JSON-ként lehet visszahozni az appba. Kiegészítésként a claude.ai Strava connectora
is használható.

## Program formátum

A teljes leírás és példa a prompt szövegében van: [`Program.kt`](app/src/main/java/hu/edzes/model/Program.kt) (`CLAUDE_PROMPT`).
Blokktípusok: `sets`, `timed`, `interval` (idő vagy táv alapú), `segment`, `repeat`.

## Fejlesztés

```bash
./gradlew testDebugUnitTest assembleRelease
```

A tesztek JVM-en futnak (Robolectric); a füstteszt képernyőképei: `app/build/outputs/roborazzi/`.
