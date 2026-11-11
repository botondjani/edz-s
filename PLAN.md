# Edzés app – terv (v0.1)

Személyes, offline-first Android app. Csak egy felhasználó, nincs saját szerver.

## Döntések

| Téma | Döntés | Indok |
|---|---|---|
| Platform | Natív Android: Kotlin + Jetpack Compose, Room (SQLite) | Health Connect csak natívan megy rendesen |
| Telepítés | GitHub Actions buildeli az APK-t → letöltöd, sideload | Nem kell Android Studio a telefonhoz |
| Fitbit | **Health Connect** olvasás (Google Health app oda szinkronizál) | Lokális, nincs Fitbit cloud API (kivezetés alatt) |
| Rögzítés | Futás/bicikli/úszás: Fitbit + telefon GPS (Google Health app) → az app importál | Air-nek nincs GPS-e; Edge-re váltáskor semmi nem változik |
| Kondi | Az app maga méri (timer + napló) | Ez a fő „saját” funkció |
| AI import | JSON bemásolás / fájl megnyitás (`.json`, share intent) | Megbízható, offline, validálható |
| Terv | Naptár + többhetes ciklus, újraindítható program | Egy hónapot egyszerre setupol |
| Strava | Minden edzés feltöltve, duplikáció-szűréssel | Strava = adat-hub |
| Analitika | Claude-ban, a hivatalos **Strava connectorral** | Nem kell saját MCP; később opcionális |

## Funkciók (MVP sorrendben)

1. **Program import** – Claude által generált JSON → validálás → előnézet → „Indítás dátuma” → naptárba ütemezés.
2. **Naptár / Ma nézet** – mai edzés, teljesítve/kihagyva, program újraindítása elölről.
3. **Kondi lejátszó** – a JSON blokkjait végigvezeti:
   - súlyzós szett: ism. × súly naplózás, automatikus pihenő-visszaszámláló,
   - intervall/HIIT/Tabata: munka/pihenő ciklusok, hang + rezgés, képernyő ébren,
   - előző alkalom értékei előtöltve (progresszió).
4. **Health Connect import** – ExerciseSession + pulzus + útvonal + távolság; automatikus párosítás a tervezett napi edzéssel (sporttípus + nap).
5. **Strava sync** – OAuth (saját Strava API app), feltöltés:
   - kondi: manuális activity, leírásban gyakorlatok/súlyok (Claude ezt is látja),
   - futás/bicikli/úszás: TCX/GPX generálás útvonallal + pulzussal → `/uploads`.
   - Duplikáció: `external_id` + időpont-ütközés ellenőrzés.
6. **Export** – teljes adatbázis JSON-ba (backup + Claude-nak feltölthető).

## Import formátum (vázlat)

```json
{
  "format": "edzes-program/v1",
  "name": "4 hetes erő + futás",
  "weeks": 4,
  "repeatable": true,
  "days": [
    {
      "week": 1, "weekday": "MON",
      "workout": {
        "title": "Felsőtest A", "sport": "strength",
        "blocks": [
          { "type": "sets", "exercise": "Fekvenyomás", "sets": 4, "reps": 8, "weight_kg": 60, "rest_s": 120 },
          { "type": "interval", "name": "Tabata burpee", "work_s": 20, "rest_s": 10, "rounds": 8 },
          { "type": "timed", "exercise": "Plank", "duration_s": 60, "sets": 3, "rest_s": 30 }
        ]
      }
    },
    {
      "week": 1, "weekday": "WED",
      "workout": { "title": "Könnyű futás", "sport": "run", "target": { "duration_min": 40, "hr_zone": 2 } }
    }
  ]
}
```

Az app mellé jár egy kész **Claude prompt**, ami pontosan ezt a formátumot kéri. Hibás JSON-nál
az app soronként megmondja, mi a gond.

## Ismert korlátok

- Health Connect alapból csak az engedélyezés előtti **30 napot** adja; régebbihez külön history-engedély kell.
- Ha az app tölt fel mindent Stravára, a **Google Health → Strava szinkront ki kell kapcsolni**, különben dupla lesz.
- Strava API app létrehozása kézi lépés (strava.com/settings/api), a client secret a telefonon marad.

## Nyitott kérdések

- UI nyelve (magyar?), egységek (kg/km).
- Kell-e strukturált futóedzés hangjelzéssel (szakaszok, zónák) – most csak célérték.
- Később: saját MCP connector, hogy Claude közvetlenül írjon a tervbe.
