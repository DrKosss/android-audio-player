# Šaptač — Android v1.0

Gotov, potpisan i instalabilan APK nalazi se ovde:

**[`dist/Saptac-v1.0.apk`](dist/Saptac-v1.0.apk)** · 2.420.458 bajtova  
SHA-256: `9a51af95f0d035c83a599084ef8426681681e4c1e14da0fa9ab7d4dbbd582d66`

Izvorni prototip i specifikacija su `saptac-prototip.html`, `saptac-dizajn.html` (v0.7, 11. 11. 2026), `saptac-ikona-final.html` i `ikone/final/`. UI je na srpskoj latinici, sa tamnom toplom paletom, ikonom iz prototipa i svih 11 ekrana.

## Šta radi u v1.0

- **Tri sekcije sa različitim pravilima:** Knjige (pozicija po fajlu i sekundi, 0,5–2×, preostalo vreme, `PONIŠTI` posle ručnog traženja), Muzika (folderi/izvođači/albumi, bez pamćenja pozicije, uvek 1×, shuffle/repeat), Emisije (nove/započete/završene, `Nastavi`, `Nasumična`, automatski sledeća, brzina po seriji).
- **Uvoz svih fajlova:** interna memorija i SD kartica, predlozi klasifikacije po imenu/obliku/tagovima, potvrda foldera, pojedinačni fajlovi i SAF folder, dug pritisak za prebacivanje sekcije, mešani fajlovi; fajlovi se ne pomeraju na disku.
- **Lokalna reprodukcija:** MP3/M4A/M4B/OGG/Opus/FLAC/WAV preko sistemskog plejera u `PlayerService` foreground servisu; `MediaSession`, stalno obaveštenje i kontrole na zaključanom ekranu; audio-focus, wake-lock, čuvanje pozicije svakih 5 s i pri pauzi/seeku.
- **Plejer:** ±30 s, prethodno/sledeće, liste/poglavlja, brzina, napredak, omot iz fajla ili opcioni internet-omot po albumu/knjizi.
- **Tajmer za spavanje:** 15/30/45/60 min, utišavanje 30/60/90 s, 60 s „grace“ perioda, produžetak pomeranjem telefona, dugmetom za jačinu ili paljenjem ekrana, noćni ekran sa tri zone i `+15 min`, kratka vibracija.
- **Podkasti:** ručno dodavanje i osvežavanje RSS feedova, preuzimanje epizode pre puštanja, broj novih, Apple iTunes Search katalog sa offline rezervom, samo HTTPS.
- **Ostalo:** podešavanja, dnevnik događaja, vodič za bateriju/„nikad ne uspavljuj“, pomoć `?`, mini-plejer, skica noćnog i zaključanog ekrana.
- Podaci ostaju na telefonu; nema naloga, reklama, praćenja, streaminga bez preuzimanja, cloud sinhronizacije, Android Auto-a, EQ-a ni svetle teme — u skladu sa „nije planirano“ u dizajnu. Pretraga, bekap, prepoznavanje preimenovanih fajlova, M4B poglavlja i markeri ostaju za v1.1.

## Instalacija na Samsung Galaxy S25 / One UI 9

1. `Podešavanja → Bezbednost i privatnost → Auto Blocker` isključi za sideload iz fajl menadžera. ADB instalacija ne podleže proveri developera.
2. Ili otvori APK u „Moji fajlovi“, ili sa računara:
   ```bash
   adb install -r dist/Saptac-v1.0.apk
   ```
3. Na prvom uvozu dozvoli pristup audio fajlovima, odnosno „Pristup svim fajlovima“ za SD karticu i foldere van standardnih medija; dozvoli i obaveštenja.
4. Za rad sa ugašenim ekranom jednom podesi `Baterija → Neograničeno` i „Nikad ne uspavljuj“ po vodiču u aplikaciji.

## Tehnički podaci

| | |
|---|---|
| Paket | `com.saptac.app` |
| Verzija | `1.0.0` (`versionCode 1`) |
| Minimum | Android 11 / API 30 |
| Cilj / compile | Android 17 / API 37 |
| Potpis | jedan stabilan ključ `signing/saptac.jks`, alias `saptac` |
| Sertifikat SHA-256 | `1a776d596ef35d09dc091331e1a2adbca6da52a4f6441c9e3f9971971c1f3cdb` |
| Shema potpisa | APK Signature Scheme v2 (`apksigner verify`: `Verifies`) |
| Launcher | `com.saptac.app.MainActivity`, labela `Šaptač` |
| Mreža | `android:networkSecurityConfig` dozvoljava samo HTTPS |

`build.log` sadrži ceo CI build, `apksigner verify --verbose --print-certs` i `aapt dump badging`. Ključ je u repozitorijumu da bi svaka naredna verzija ostala nadogradiva istim potpisom; za javnu distribuciju ga treba prebaciti u tajnu čuvarnicu.

## Tehnička napomena o odstupanju od dizajn-dokumenta

Dizajn-dokument predlaže Jetpack Compose, Media3/ExoPlayer i Room. Ova v1 koristi nativne programske Android `View`-ove, sistemski `MediaPlayer` + `MediaSession` u foreground servisu i lokalnu `SharedPreferences`/JSON pohranu. To su funkcionalni ekvivalenti za iste ekrane, osobine i potpisani APK, izabrani zbog potpune kontrole nad gustim, tačno nacrtanim prototipom i zbog malog APK-a bez dodatnih mrežnih biblioteka. Spoljašnje ponašanje, v1.0 obim, API 30–37, HTTPS-only, jedan ključ i dizajn su po specifikaciji; Compose/Media3/Room nisu ugrađeni.

## Ponovna izgradnja

```bash
./gradlew assembleRelease
```

Ili pokreni **Build Šaptač APK** workflow u GitHub Actions. `.github/workflows/build-apk.yml` koristi stabilan Gradle 8.9 wrapper, JDK 17, Android platformu 37, generiše/zadržava isti ključ, gradi `assembleRelease`, proverava potpis i vraća `dist/Saptac-v1.0.apk` na granu. Runner isporučuje API 37 kao `android-37.2`, pa workflow pravi kanonsku `platforms/android-37` kopiju sa usklađenim `source.properties`/`package.xml` pre nego što AGP konfiguriše projekat.

Prototip HTML-ovi ostaju netaknuti kao izvor istine za dizajn.
