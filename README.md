# Šaptač — Android v1.1.2

Gotov, potpisan i instalabilan APK nalazi se ovde:

**[`dist/Saptac-v1.0.apk`](dist/Saptac-v1.0.apk)** · 2.517.206 bajtova  
SHA-256: `4afb90c776672f4d77d9e1c8ff46a8714196acc89fc614cdd5211090badace85`

Izvorni prototip i specifikacija su `saptac-prototip.html`, `saptac-dizajn.html` (v0.7, 11. 11. 2026), `saptac-ikona-final.html` i `ikone/final/`. UI je na srpskoj latinici, sa tamnom toplom paletom i ikonom iz prototipa. Aplikacija prati prototip u tekstu, rasporedu, veličinama ikonica i tokovima; jedini dodaci su četvrta sekcija „Stand up“, ručna ispravka omota (samo preko olovke), izvoz liste fajlova i virtuelno sređivanje biblioteke sa opcionim internet obogaćivanjem (skriveno u `Podešavanjima`); skica zaključanog ekrana koja se otvarala na katanac i obe iPhone linije na dnu su uklonjene, a donja navigacija je vidljiva na apsolutno svim ekranima, uključujući plejer i podešavanja.

## Šta je novo u v1.1.2 — brži prelazak između sekcija

- **Uzrok usporenja:** pri svakom kliku na `Muzika / Knjige / Emisije / Stand up` se za svaki od 1.341 fajla iznova parsirala velika lokalna `SharedPreferences` JSON baza (folderi, override, normalizacije, pozicije, `done`, nedavne grupe), zatim su se grupe ponovo gradile, a status i omoti su se čitali više puta po redu. `signature()` je uz to svakih 500 ms ponovo čitao ceo JSON feedova.
- **Jedno parsiranje po ključu:** `Store` sada drži keš parsiranih `JSONObject`/`JSONArray` vrednosti. Upisi rade nad kopijom, pa deljeni keš nikad ne menja neko drugi tok; `Store.epoch()` se menja posle svakog upisa i time odbacuje izvedene keševe.
- **Grupe se ne prave iznova:** `LibraryManager.groups()` kešira gotovu listu grupa po sekciji i prikazu (`folderi / izvođači / albumi`). Keš važi dok se ne promeni biblioteka, normalizacija ili lokalna baza; ponovni klik na isti tab više ne skenira svih 1.341 fajl.
- **Manje posla po redu:** `Knjige` i `Stand up` sada izračunaju poziciju i „završeno“ jednom po grupi, `Emisije` prolaze kroz epizode jednom umesto dva puta, a `signature()` koristi `Store.feedCount()` umesto da pravi sve `Feed` objekte svakih pola sekunde.
- **Omoti bez kočenja i bez rasta memorije:** ručni/ugrađeni i internet omoti koriste ograničen `LruCache` bitmapa; internet omoti se učitavaju umanjeni na najviše 256 px, a negativni rezultat se zapamti, pa se za svaku grupu više ne otvara i ne dekoduje isti fajl uzalud. Prioritet **tvoja olovka > ugrađeni omot > keširani internet omot > generički gradijent** ostaje netaknut.
- **Nema promene ponašanja:** četiri taba, stalno vidljiva donja navigacija, minimiziranje/`Back`, reprodukcija u pozadini preko obaveštenja, birač omota samo na olovku, virtuelno sređivanje, snapshot/`Vrati sve`, pravila za mobilni internet i „samo predlog, ja potvrdim“ ostaju tačno kao u v1.1.1. Nema novih mrežnih poziva, novih ekrana ni promene fajlova na disku.
- **Verzija:** `versionCode 13`, `versionName 1.1.2`; CI `43785919122` (`ASSEMBLE_EXIT=0`, bez `NO APK PRODUCED`, `Verifies`, v2 `true`), artifact commit `4ec5620`.

## Ranije ispravke (v1.1.1) — Faza 2 sređivanja biblioteke

- **Svih pet odobrenih izvora:** `Apple/iTunes` (katalog + RSS sa originalnim engleskim naslovima epizoda), `Wikipedia` (wikitext spisak epizoda), `BBC` (programme guide, best-effort), `MusicBrainz` (muzika) i `Open Library` (knjige, sa URL-om omota), plus `Podcast Index` kada se unese besplatni ključ + tajna. Svi su HTTPS, bez hardkodovanih kredencijala.
- **Podcast Index ključ/tajna se unose SAMO u aplikaciji:** `Podešavanja → SREĐIVANJE · INTERNET → Podcast Index ključ / tajna`. Ne traže se u chatu, ne upisuju se u repozitorijum i **brišu se iz izvezenog JSON-a**. Bez njih se taj jedan izvor preskače, ostali rade. Potpis zahteva je `HmacSHA1(key + timestamp)` sa `X-Auth-Key` / `X-Auth-Date` / `Authorization` zaglavljima.
- **Wi-Fi podrazumevano, mobilni uz potvrdu svaki put:** kada posle uvoza (ili na `Proveri na internetu`) nema Wi-Fi, aplikacija prikaže `Koristi mobilni internet za ovu proveru?` → `Da / Ne`. Bez `Da` ne šalje ništa; na `Ne` ostaje čisti offline rezultat iz Faze 1. Nema tihog trošenja paketa. Prekidač `Koristi internet za sređivanje` može potpuno isključiti mrežu.
- **Internet nikad ne primenjuje naslov automatski:** svaki pogodak postaje **predlog** u `Podešavanja → SREĐIVANJE → Sređivanje biblioteke`, sa izvorom, poverenjem, razlogom i oznakom `omot`. Tvoj klik `Primeni` i dalje odlučuje; 45 neraspoređenih + 115 mešanih i svi sumnjivi slučajevi ostaju „samo predlog, ja potvrdim“. Lokalni sigurniji predlog se ne gazi slabijim internet pogotkom.
- **Keš omota sa ETag/TTL:** jedna mala slika po seriji/albumu/izvođaču u privatnom `filesDir/norm-art`, `If-None-Match` / `304` / 30-dnevni TTL u `normalizationArtCache`. Prioritet: **tvoja olovka > ugrađeni omot > keširani internet omot > generički gradijent**. `coverKeyOf()` i dalje deli ručni omot na sve virtuelne sezone.
- **Rotirajući obilazak cele biblioteke:** po jednom pokretanju se pita najviše 20 jedinstvenih serija/albuma, a `normalizationOnlineCursor` pamti gde se stalo. Naredni uvoz ili `Proveri na internetu` nastavlja na sledećih 20, pa kroz nekoliko pokretanja bude pokrivena cela biblioteka bez stotina zahteva odjednom.
- **Privatnost:** na mrežu ide samo normalizovani upit (ime serije / album / izvođač / `S##E##`), ne pune putanje, ne `AudioItem.uri`, ne imena tvojih foldera, ne ID uređaja ni podaci iz eksporta. `NormalizedMetadata` je proširen poljima `artworkUrl`, `onlineSource`, `onlineConfidence`, `onlineReason`, `onlineCheckedAt`; izvoz nosi i `normalizationArtCache` + `normalizationOnlineCursor`.
- **Faza 3 (još nije urađena):** uvoz/ponovna primena izvezene mape normalizacije i napredniji batch pregled cele biblioteke ostaju za narednu verziju.
- **Verzija:** `versionCode 12`, `versionName 1.1.1`; CI `43670276240` (`ASSEMBLE_EXIT=0`, bez `NO APK PRODUCED`, `Verifies`, v2 `true`).

## Ranije ispravke (v1.1.0.1) — vodeće nule za sezone i epizode

- **Grupe se sada zovu `Sezona 01`, `Sezona 02` … `Sezona 10`**, umesto `Sezona 1`, `Sezona 2` … `Sezona 10`. Jednocifrene sezone dobijaju vodeću nulu, pa se više ne dešava da `Sezona 10` stoji ispred `Sezona 2`.
- **Sve S/E oznake su na dve cifre:** `S1E2` → `S01E02`, `TUT S2E3` → `S02E03`, `Series 2, Episode 3` → `S02E03`, goli `Episode 2` → `E02`, specijal `S2 · Christmas Special` → `S02 · Christmas Special`. Sort-ključ je `S01E02`, `S02E03` … `S10E12`.
- **Ispravljen redosled virtuelnih sezona:** `LibraryManager.groups()` za `Emisije / Folderi` sada sortira po sintetičkom ključu `…|season|S01 / S02 / … / S10 / SPECIAL`, a ne po naslovu grupe. Ostale sekcije i prikazi zadržavaju raniji sort po naslovu.
- **Migracija već upisanih metapodataka:** pri čitanju `NormalizedMetadata` se `displayTitle`, `sortKey`, `seasonGroupKey` i `seasonGroupTitle` automatski dopune nulama (`padSeLabel`, `padSeasonKey`, `padSeasonTitle`). Ne moraš ponovo da uvoziš fajlove da bi stare `Sezona 1` grupe postale `Sezona 01`.
- **Faza 2 je naknadno dodata u v1.1.1** — ova ispravka je bila samo u okviru Faze 1; tada još nije bilo mrežnih poziva ni API ključa.
- **Verzija:** `versionCode 11`, `versionName 1.1.0.1`; CI `43667949820` (`ASSEMBLE_EXIT=0`, bez `NO APK PRODUCED`, `Verifies`, v2 `true`).

## Ranije ispravke (v1.1.0) — Faza 1 sređivanja biblioteke

- **Automatski posle svakog uvoza:** čim se završi `Uvezi N foldera`, izbor SAF foldera ili izbor pojedinačnih fajlova, pokreće se offline parser. Ne čeka se ručno dugme. Pre svakog pokretanja pravi se snapshot, a `Vrati sve` vraća tačno stanje pre poslednjeg pokretanja.
- **Isključivo virtuelno:** novi sloj `NormalizedMetadata` (`seriesTitle`, `season`, `episode`, `episodeTitle`, `special`, `displayTitle`, `sortKey`, `seasonGroupKey`, `source`, `confidence`, `reason`) piše se u `Store`. Originalni `AudioItem`, URI, ime fajla i lokacija na disku ostaju netaknuti — ništa se ne preimenuje, ne pomera, ne briše.
- **Originalni engleski naslov + `S##E##`:** kanonski prikaz je `The Unbelievable Truth · S02E03 · Cheese`, sort-ključ `S02E03`, specijali u `Specijali`. Parser prepoznaje šest obrazaca iz tvog eksporta: `... - S## - E##`, `TUT S##E##`, `Series ##, Episode #`, goli `Episode N`, `NNNN - naslov` i `S02 - ESP - Christmas Special`. Naslov epizode se ne prevodi.
- **Sigurno odmah, sumnjivo na potvrdu:** jednoznačni `S/E` pogoci u već raspoređenim `Emisije` primenjuju se sami. Svih 45 neraspoređenih, 115 mešanih, 11 golih `Episode N`, duplikati `Episode 2/3/4/6`, specijali i `NNNN - naslov` dobijaju samo **predlog** — ništa se ne menja bez tvog klika `Primeni`. `Zadrži original` trajno sklanja predlog i ne vraća ga pri narednom uvozu.
- **Virtuelne sezone:** `LibraryManager.groups()` sada deli potvrđene epizode u `Sezona 1 … Sezona N / Specijali` i sortira ih po `sortKey`. Sintetički ključ sezone preko `coverKeyOf()` i dalje koristi ručni omot celog foldera, pa olovka važi za sve sezone. Virtuelni naslov se prikazuje u listama, plejeru, mini-plejeru, `Sledeće`, noćnom ekranu i izvozu.
- **Novi ekran `Sređivanje biblioteke`:** dostupan isključivo iz `Podešavanja → SREĐIVANJE`. Prikazuje `Pre → Posle`, izvor (`local`), poverenje i razlog, sa dugmadima `Primeni`, `Zadrži original` i `Vrati sve`. Četiri taba i prototipske liste nisu menjani; nema novih tekstova na glavnim ekranima ni novih toastova.
- **Proširen izvoz:** `saptac-library-export-v1` sada uz svaki fajl nosi `normalized`, `normalizationProposal`, `displayTitle`; uz grupu `coverKey` i `isVirtualSeason`; u `rawStores` `normalizations`, `normalizationProposals`, `normalizationDismissed`, `normalizationSnapshot`; u `counts` `normalizedApplied` i `normalizationProposals`.
- **Bez mreže u ovoj fazi:** sređivanje ne pravi nijedan internet poziv i ne šalje pune putanje, URI-je, imena foldera ni podatke uređaja. Postojeće opciono preuzimanje omota preko iTunes-a ostaje netaknuto.
- **Odluke za Fazu 2 (sada implementirane u v1.1.1):** odobreni izvori su Apple/iTunes + BBC/Wikipedia + MusicBrainz + Open Library + Podcast Index; Podcast Index traži besplatni API ključ koji se unosi u `Podešavanjima` aplikacije (ne u chatu). Mreža podrazumevano samo Wi-Fi; na mobilnom je dozvoljena samo uz prethodnu potvrdu korisnika **svaki put** kada je potrebna. 45 neraspoređenih i 115 mešanih ostaju „samo predlog, ja potvrdim“.
- **Verzija:** `versionCode 10`, `versionName 1.1.0`; CI `43666127220` (`ASSEMBLE_EXIT=0`, bez `NO APK PRODUCED`, `Verifies`, v2 `true`).

## Ranije ispravke (v1.0.8)

- **Fajl koji svira je označen na listi:** pri povratku sa plejera (strelica nadole, sistemsko `Back` ili donja navigacija) red sa trenutnim fajlom ima tamno-ćilibarsku pozadinu, ćilibarski naslov i mali `play` znak; grupa kojoj pripada je takođe označena. Lista se pre prvog crtanja pomeri na taj red, bez skoka na vrh. Oznaka se osvežava i ako se fajl u redu promeni dok je lista otvorena. Pokrivene su liste knjiga, emisija, `Stand up`, muzičkih foldera, epizoda, numera, poglavlja/liste, mešanih fajlova i feed epizoda.
- **Menjanje omota samo na olovku:** dodir na celu sliku u plejeru više ne otvara birač slika. Birač se pokreće isključivo klikom na malu olovku u donjem desnom uglu omota; dug dodir na olovku skida ručni izbor i vraća ugrađeni/automatski omot. Uklonjen je i dugi dodir na sličice grupa u listama, koji je ranije isto otvarao birač.
- **Navigacija uvek vidljiva:** `Muzika / Knjige / Emisije / Stand up` su sada i u samom plejeru i u `Podešavanjima`. Raniji izuzetak za ta dva ekrana je uklonjen; sadržaj, listići, noćni ekran i toast imaju rezervisanih 58 dp, bez linije iznad navigacije. Time je poništeno ranije pravilo „svuda osim plejera i podešavanja“.
- **Uvoz automatski čekira već uvežene foldere:** pri ulasku u `Uvezi fajlove` svi folderi koji već imaju dodeljenu sekciju (ili je nasleđuju od najbližeg roditelja) unapred su čekirani, kao i `Pojedinačni fajlovi` ako su ranije uvezeni. Potvrda i dalje glasi `Uvezi N foldera` / `Izaberi najmanje jedan folder`, a ručno odčekiravanje se poštuje.
- **Verzija:** `versionCode 9`, `versionName 1.0.8`; CI `43662151755` (`ASSEMBLE_EXIT=0`, `Verifies`, v2 `true`).

## Ranije ispravke (v1.0.7)

- **Dugme za minimiziranje plejera radi:** strelica nadole sada zaista zatvara veliki plejer. Ako je plejer otvoren iz nekog ekrana, vraća se na taj ekran; ako je otvoren samostalno posle povratka u aplikaciju, odlazi na početni ekran odgovarajuće sekcije (`Knjige / Emisije / Muzika / Stand up`) gde ostaju mini-plejer i donja navigacija. Sistemsko `Back` dugme radi isto. Rotacija ekrana više ne vraća minimizirani plejer nazad, a ako Android ubije proces dok je plejer bio otvoren, i dalje se vraća u poslednji klip.

## Ranije ispravke (v1.0.6)

- **Uklonjena je i poslednja linija na dnu:** obrisana je tanka `border-top` linija iznad `Muzika / Knjige / Emisije / Stand up`. Prototip je rađen za iPhone; na Androidu ispod navigacije sada ostaje samo čista tamna pozadina. Visina rezervisanog prostora je sa 59 dp smanjena na 58 dp, pa sadržaj, listići, noćni ekran i toast ponovo tačno naležu na navigaciju bez viška piksela.

## Ranije ispravke (v1.0.5)

- **Bez linije na dnu:** uklonjena je kratka horizontalna „home“ linija ispod sadržaja. Tanka prototipska linija iznad navigacije tada je još ostala, pa je uklonjena u v1.0.6.
- **Povratak u poslednji klip:** pri svakom povratku u aplikaciju, pa i posle više sati/dana ili ubijenog procesa, otvara se plejer sa poslednjim fajlom i poslednjom pozicijom. Ceo queue, indeks, pozicija, brzina, shuffle/repeat i metapodaci se trajno upisuju u `lastPlayer`; `PlayerService` ih obnavlja pri pokretanju, a `onResume`/`onNewIntent` vraćaju ekran na plejer. Povratak iz sistemskog izbora fajlova/slike ne prekida taj tok.
- **Sistematizacija po celoj putanji:** predlog sekcije više ne gleda samo ime poslednjeg foldera, nego sve foldere i podfoldere od korena do fajla. Pobeđuje onaj koji je najbliži fajlu: `/Muzika/Stand-up/fajl` → `Stand up`, `/Stand-up/Muzika/fajl` → `Muzika`. Isto pravilo važi i za ručno dodeljenu sekciju nekog roditelja. Prepoznaju se `stand up/standup/komedija/comedy`, `knjiga/book/audiobook`, `podkast/radio/emisija/serija` i `muzika/music/album/mp3`.
- **Izvoz liste fajlova:** u `Podešavanja → IZVOZ` je dugme `Izvezi listu uvezenih fajlova`. Bira se lokacija i piše `saptac-fajlovi-YYYYMMDD-HHmm.json` (`saptac-library-export-v1`). JSON sadrži svaki `AudioItem` (id, URI, naslov, izvođač, album, trajanje, folder, veličinu, datum, track), sve segmente putanje, najbliži predlog sekcije, efektivnu sekciju i njen izvor, poziciju, status `done`, članstvo u svim grupama (folderi/izvođači/albumi), ručne omote, „mešane“ fajlove, foldere, feedove, SAF korene, podešavanja, pozicije, override, nedavne grupe i poslednji plejer. Format je namerno JSON jer se najlakše direktno dodaje na Arenu radi dalje obrade.
- **Donja navigacija uvek vidljiva:** `Muzika / Knjige / Emisije / Stand up` su sada na svim ekranima, uključujući detalje, uvoz, raspoređivanje, listiće i noćni ekran. U v1.0.5 je još važio izuzetak za sam plejer i podešavanja; od v1.0.8 navigacija više nije skrivena nigde. Sadržaj ima rezervisan prostor, a listići/noćni ekran su podignuti iznad navigacije.

## Ranije ispravke (v1.0.4)

- **Ikone kao u prototipu:** ispravljeni su oštećeni `pathData` vektori za `prethodno/na početak`, `30 s nazad` i `30 s napred`, kao i za play, check, strelice, refresh i shuffle. `i-prev`/`i-next` sada imaju puni trougao i popunjenu crtu, a `i-b30`/`i-f30` kružnu strelicu, popunjen vrh i centrirani broj `30` u tačnoj razmeri iz SVG `viewBox`-a. Broj je sada vidljiv i na noćnom ekranu.
- **Ručna ispravka omota:** v1.0.4 je uvela trajni ručni omot po grupi, koji ima prednost nad ugrađenim i automatski preuzetim omotom i prikazuje se u listama, mini-plejeru, `MediaSession` i obaveštenju. U v1.0.8 je ulaz sužen samo na olovku: dodir na celu sliku i dug dodir na sličicu grupe više ne otvaraju birač; dug dodir na olovku skida ručni izbor i vraća automatski.
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
| Verzija | `1.1.2` (`versionCode 13`) |
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
