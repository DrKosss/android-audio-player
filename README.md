# Šaptač — Android v1.0.7

Gotov, potpisan i instalabilan APK nalazi se ovde:

**[`dist/Saptac-v1.0.apk`](dist/Saptac-v1.0.apk)** · 2.437.454 bajtova  
SHA-256: `01176731e42695adc0f1fc582fbfb306d057596b054bcb355c5ad53d5f0eb3c1`

Izvorni prototip i specifikacija su `saptac-prototip.html`, `saptac-dizajn.html` (v0.7, 11. 11. 2026), `saptac-ikona-final.html` i `ikone/final/`. UI je na srpskoj latinici, sa tamnom toplom paletom i ikonom iz prototipa. Aplikacija prati prototip u tekstu, rasporedu, veličinama ikonica i tokovima; jedini dodaci su četvrta sekcija „Stand up“, ručna ispravka omota i izvoz liste fajlova; skica zaključanog ekrana koja se otvarala na katanac i obe iPhone linije na dnu su uklonjene, a donja navigacija je vidljiva na svim ekranima osim plejera i podešavanja.

## Šta je novo u v1.0.7

- **Dugme za minimiziranje plejera radi:** strelica nadole sada zaista zatvara veliki plejer. Ako je plejer otvoren iz nekog ekrana, vraća se na taj ekran; ako je otvoren samostalno posle povratka u aplikaciju, odlazi na početni ekran odgovarajuće sekcije (`Knjige / Emisije / Muzika / Stand up`) gde ostaju mini-plejer i donja navigacija. Sistemsko `Back` dugme radi isto. Rotacija ekrana više ne vraća minimizirani plejer nazad, a ako Android ubije proces dok je plejer bio otvoren, i dalje se vraća u poslednji klip.

## Ranije ispravke (v1.0.6)

- **Uklonjena je i poslednja linija na dnu:** obrisana je tanka `border-top` linija iznad `Muzika / Knjige / Emisije / Stand up`. Prototip je rađen za iPhone; na Androidu ispod navigacije sada ostaje samo čista tamna pozadina. Visina rezervisanog prostora je sa 59 dp smanjena na 58 dp, pa sadržaj, listići, noćni ekran i toast ponovo tačno naležu na navigaciju bez viška piksela.

## Ranije ispravke (v1.0.5)

- **Bez linije na dnu:** uklonjena je kratka horizontalna „home“ linija ispod sadržaja. Tanka prototipska linija iznad navigacije tada je još ostala, pa je uklonjena u v1.0.6.
- **Povratak u poslednji klip:** pri svakom povratku u aplikaciju, pa i posle više sati/dana ili ubijenog procesa, otvara se plejer sa poslednjim fajlom i poslednjom pozicijom. Ceo queue, indeks, pozicija, brzina, shuffle/repeat i metapodaci se trajno upisuju u `lastPlayer`; `PlayerService` ih obnavlja pri pokretanju, a `onResume`/`onNewIntent` vraćaju ekran na plejer. Povratak iz sistemskog izbora fajlova/slike ne prekida taj tok.
- **Sistematizacija po celoj putanji:** predlog sekcije više ne gleda samo ime poslednjeg foldera, nego sve foldere i podfoldere od korena do fajla. Pobeđuje onaj koji je najbliži fajlu: `/Muzika/Stand-up/fajl` → `Stand up`, `/Stand-up/Muzika/fajl` → `Muzika`. Isto pravilo važi i za ručno dodeljenu sekciju nekog roditelja. Prepoznaju se `stand up/standup/komedija/comedy`, `knjiga/book/audiobook`, `podkast/radio/emisija/serija` i `muzika/music/album/mp3`.
- **Izvoz liste fajlova:** u `Podešavanja → IZVOZ` je dugme `Izvezi listu uvezenih fajlova`. Bira se lokacija i piše `saptac-fajlovi-YYYYMMDD-HHmm.json` (`saptac-library-export-v1`). JSON sadrži svaki `AudioItem` (id, URI, naslov, izvođač, album, trajanje, folder, veličinu, datum, track), sve segmente putanje, najbliži predlog sekcije, efektivnu sekciju i njen izvor, poziciju, status `done`, članstvo u svim grupama (folderi/izvođači/albumi), ručne omote, „mešane“ fajlove, foldere, feedove, SAF korene, podešavanja, pozicije, override, nedavne grupe i poslednji plejer. Format je namerno JSON jer se najlakše direktno dodaje na Arenu radi dalje obrade.
- **Donja navigacija uvek vidljiva:** `Muzika / Knjige / Emisije / Stand up` su sada na svim ekranima, uključujući detalje, uvoz, raspoređivanje, listiće i noćni ekran. Skriva se samo u samom plejeru i u podešavanjima, kako je traženo. Sadržaj ima rezervisan prostor, a listići/noćni ekran su podignuti iznad navigacije.

## Ranije ispravke (v1.0.4)

- **Ikone kao u prototipu:** ispravljeni su oštećeni `pathData` vektori za `prethodno/na početak`, `30 s nazad` i `30 s napred`, kao i za play, check, strelice, refresh i shuffle. `i-prev`/`i-next` sada imaju puni trougao i popunjenu crtu, a `i-b30`/`i-f30` kružnu strelicu, popunjen vrh i centrirani broj `30` u tačnoj razmeri iz SVG `viewBox`-a. Broj je sada vidljiv i na noćnom ekranu.
- **Ručna ispravka omota:** dodir na omot u plejeru (ili mala olovka u uglu) otvara sistemski izbor slike; izbor se trajno pamti po grupi, ima prednost nad ugrađenim i automatski preuzetim omotom, prikazuje se u listama, mini-plejeru, `MediaSession` i obaveštenju. Dug dodir na omot skida ručni izbor i vraća automatski. Dug dodir na sličicu grupe u listi takođe otvara izbor slike.
- **Bez prozora na katanac:** uklonjeni su dugme sa katancem, `lockOverlay()`, `Bus.lockPreview` i `ic_lock.xml`. Sistemske kontrole preko `MediaSession` ostaju; nema više internog ekrana „zaključanog prikaza“.

## Ranije ispravke (v1.0.3)

- **Ispravke u v1.0.3:** sistemski dugmići na dnu više ne prekrivaju dugmiće za reprodukciju (rezervisan je prostor za `WindowInsets` navigacione trake, uključujući i listiće i noćni ekran); posle pauze reprodukcija se pouzdano nastavlja (jedan stalni `AudioFocusRequest` umesto novog pri svakom `resume()`, koji je slao `AUDIOFOCUS_LOSS` i odmah pauzirao, plus obnova stanja ako je servis ponovo pokrenut); izbor foldera više ne flickeruje na vrh — oznaka sekcije i dugme za potvrdu ažuriraju se u mestu, a kada se ekran ipak ponovo nacrta, skrol se vraća pre prvog crtanja (`OnPreDrawListener`).
- **Vraćeno na prototip:** uklonjeni su dugme `?` i pomoć, ekrani za bateriju i dnevnik događaja, dodatne poruke, fusnota sa verzijom, uvećane ikonice i svi tekstovi kojih nema u `saptac-prototip.html`. Ikonice sekcija su vraćene na 21 dp, a redovi sa folderima na izgled iz prototipa.
- **Četvrta sekcija — „Stand up“:** četvrti tab u donjoj traci i četvrta sekcija pri uvozu, raspoređivanju, mešanim fajlovima i prebacivanju. Koristi isti prikaz kao „Knjige“ (omot, `Sve / U toku / Nove / Završene`, pozicija, preostalo vreme, `PONIŠTI`) — bez novih tabova, filtera ili naziva. Folderi sa `stand up`, `standup`, `komedija`, `comedy` dobijaju automatski predlog sekcije.
- **Potvrda izbora:** na ekranu „Uvoz“ uvek je vidljivo primarno dugme `Uvezi N foldera` (odnosno `Izaberi najmanje jedan folder`) koje potvrđuje izabrane foldere; za pojedinačne fajlove se posle izbora otvara listić sa sekcijama i tek tu se potvrđuje smeštanje.
- **Skrol ostaje na mestu:** pri svakom izboru sekcije za folder lista se više ne vraća na vrh — `ScrollView` zadržava prethodnu poziciju pri ponovnom crtanju istog ekrana.
- **Tri sekcije iz prototipa:** Knjige (pozicija po fajlu, 0,5–2×, preostalo vreme, `PONIŠTI`), Muzika (folderi/izvođači/albumi, uvek 1×, shuffle/repeat), Emisije (nove/započete/završene, `Nastavi`, `Nasumična`, automatski sledeća, brzina po seriji).
- **Uvoz:** interna memorija i SD kartica, predlozi klasifikacije po imenu/obliku/tagovima, pojedinačni fajlovi, dug pritisak za prebacivanje sekcije, mešani fajlovi; fajlovi se ne pomeraju na disku.
- **Lokalna reprodukcija:** MP3/M4A/M4B/OGG/Opus/FLAC/WAV preko sistemskog plejera u `PlayerService` foreground servisu; `MediaSession`, stalno obaveštenje i kontrole u sistemskom zaključanom ekranu; audio-focus, wake-lock, čuvanje pozicije svakih 5 s i pri pauzi/seeku.
- **Plejer:** ±30 s, prethodno/sledeće, liste/poglavlja, brzina, napredak, omot iz fajla, opcioni internet-omot po albumu/knjizi ili ručno izabrana slika koja ima prednost nad oba.
- **Tajmer za spavanje:** 15/30/45/60 min, utišavanje 30/60/90 s, 60 s „grace“ perioda, produžetak pomeranjem telefona, dugmetom za jačinu ili paljenjem ekrana, noćni ekran sa tri zone i `+15 min`, kratka vibracija.
- **Podkasti:** ručno dodavanje i osvežavanje RSS feedova, preuzimanje epizode pre puštanja, broj novih, Apple iTunes Search katalog sa offline rezervom, samo HTTPS.
- **Ostalo:** podešavanja, mini-plejer, skica noćnog ekrana. Podaci ostaju na telefonu; nema naloga, reklama, praćenja, streaminga bez preuzimanja, cloud sinhronizacije, Android Auto-a, EQ-a ni svetle teme. Pretraga, bekap, prepoznavanje preimenovanih fajlova, M4B poglavlja i markeri ostaju za v1.1.

## Instalacija na Samsung Galaxy S25 / One UI 9

1. `Podešavanja → Bezbednost i privatnost → Auto Blocker` isključi za sideload iz fajl menadžera. ADB instalacija ne podleže proveri developera.
2. Ili otvori APK u „Moji fajlovi“, ili sa računara:
   ```bash
   adb install -r dist/Saptac-v1.0.apk
   ```
3. Na prvom uvozu dozvoli pristup audio fajlovima, odnosno „Pristup svim fajlovima“ za SD karticu i foldere van standardnih medija; dozvoli i obaveštenja.
4. Za rad sa ugašenim ekranom u sistemskim podešavanjima jednom postavi `Baterija → Neograničeno` i „Nikad ne uspavljuj“.

## Tehnički podaci

| | |
|---|---|
| Paket | `com.saptac.app` |
| Verzija | `1.0.7` (`versionCode 8`) |
| Minimum | Android 11 / API 30 |
| Cilj / compile | Android 17 / API 37 |
| Potpis | jedan stabilan ključ `signing/saptac.jks`, alias `saptac` |
| Sertifikat SHA-256 | `1a776d596ef35d09dc091331e1a2adbca6da52a4f6441c9e3f9971971c1f3cdb` |
| Shema potpisa | APK Signature Scheme v2 (`apksigner verify`: `Verifies`) |
| Launcher | `com.saptac.app.MainActivity`, labela `Šaptač` |
| Mreža | `android:networkSecurityConfig` dozvoljava samo HTTPS |

`build.log` sadrži ceo CI build, `apksigner verify --verbose --print-certs` i `aapt dump badging`. Ključ je u repozitorijumu da bi svaka naredna verzija ostala nadogradiva istim potpisom; za javnu distribuciju ga treba prebaciti u tajnu čuvarnicu.

## Tehnička napomena o odstupanju od dizajn-dokumenta

Dizajn-dokument predlaže Jetpack Compose, Media3/ExoPlayer i Room. Ova v1 koristi nativne programske Android `View`-ove, sistemski `MediaPlayer` + `MediaSession` u foreground servisu i lokalnu `SharedPreferences`/JSON pohranu. To su funkcionalni ekvivalenti za iste ekrane, osobine i potpisani APK, izabrani zbog potpune kontrole nad gustim, tačno nacrtanim prototipom i zbog malog APK-a bez dodatnih mrežnih biblioteka. Spoljašnje ponašanje, v1 obim, API 30–37, HTTPS-only, jedan ključ i dizajn su po specifikaciji; Compose/Media3/Room nisu ugrađeni.

## Ponovna izgradnja

```bash
./gradlew assembleRelease
```

Ili pokreni **Build Šaptač APK** workflow u GitHub Actions. `.github/workflows/build-apk.yml` koristi stabilan Gradle 8.9 wrapper, JDK 17, Android platformu 37, generiše/zadržava isti ključ, gradi `assembleRelease`, proverava potpis i vraća `dist/Saptac-v1.0.apk` na granu. Ako `assembleRelease` ne uspe, workflow ipak objavljuje `build.log`, ali završava kao neuspešan. Runner isporučuje API 37 kao `android-37.2`, pa workflow pravi kanonsku `platforms/android-37` kopiju sa usklađenim `source.properties`/`package.xml` pre nego što AGP konfiguriše projekat.

Prototip HTML-ovi ostaju netaknuti kao izvor istine za dizajn.
