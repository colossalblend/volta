# Google Play listing

What goes into the Play Console's store listing: the texts and the pictures.

- `listing-ru.md`, `listing-en.md` — app name (≤ 30 characters), short description
  (≤ 80) and full description (≤ 4000). Only what the app really does; no servers come
  with it, and the texts say so.
- `ru/`, `en/` — the pictures for each language:
  - `phone-01…08-*.png` — phone screenshots, 1080 × 1920;
  - `tv-01…02-*.png` — Android TV screenshots, 1920 × 1080;
  - `feature-graphic.png` — the feature graphic, 1024 × 500.

## How the pictures are made

Nothing is drawn by hand. The screens are the app's real composables, drawn under
Robolectric with sample data: fictional servers on documentation addresses
(203.0.113.x, 198.51.100.x), a subscription called «Мой VPN», made-up app names with
plain letter icons. See `app/src/test/java/com/ars3nb/volta/ui/shots/StoreShots.kt`.
Routing is drawn without the tab bar under it, so that the ad filter's card, Strict mode
and the torrent switch fit on one screen. Screens that need the ViewModel get a real one over a test database; the network is shut
off in the test, so nothing on them comes from the machine that drew them.

The phone screens are drawn at 392 × 665 dp, 2x; the TV ones at 1280 × 720 dp, 1.5x,
which is 1920 × 1080 as it is; the disc alone, for the feature graphic. Each language
gets its own sample library (`ru`: «Мой VPN», Russian server names; `en`: «My VPN").

`tools/store/compose.py` then puts each phone screen, pixel for pixel, into a flat frame
with a thin copper rim on the app's background and writes the caption above it in the
app's own fonts: a headline in Unbounded (two lines at most) and one line in Onest, the
same sizes on every picture. The captions live in that script, one list per language;
it stops with "caption does not fit" rather than shrink or clip one.

## Making them again

1. Run the workflow: Actions → «Store listing pictures» → Run workflow, or

   ```sh
   gh workflow run store.yml --ref <branch>
   ```

   It also runs by itself on a push to a `store/…` branch that touches the sources above.
2. Download the result: `gh run download <run id> -n store-pictures -D /tmp/store`.
   `raw/<lang>/` holds the bare screens, `play/<lang>/` the finished pictures.
3. Look at every picture, then copy `play/<lang>/*` here and commit.

Where artifacts can't be downloaded, run it with `-f commit=true` on a `store/…` branch:
the job then commits the finished pictures into `store/play/<lang>/` on that branch
("Store: pictures from run …"), and `git pull` brings them in to look at. Squash those
commits into yours before merging if you like.

If a screen stalls (no new screen for four minutes), the job prints the test JVM's
threads into the log, stops it and still composes whatever was drawn. Each language's
`raw/<lang>/progress.txt` in the artifact says how far it got.

Only the composing step, without CI (needs Python 3 and Pillow):

```sh
python tools/store/compose.py --raw /tmp/store/raw --out store/play
```

## Another language

Add a subclass at the end of `StoreShots.kt` with the language in its qualifiers
(`@Config(qualifiers = "de-rDE-w392dp-h665dp-xhdpi")` and `StoreShots("de")`), sample
names for it in the companion there, and the captions under the same key in `PHONE` and
`FEATURE` in `compose.py`.

## Policy notes

- Play's Device and Network Abuse policy does not let an app block or interfere with
  ads shown by other apps, and since November 2022 that is spelled out for VpnService
  apps. The ad filter (Routing → Ad blocking) ships in the `play` flavour too, and the
  sixth picture and one line of the full description mention it. Before submitting,
  decide whether the `play` build keeps it; if not, drop that line and swap the sixth
  picture's caption for the torrent and site rules.
- The texts say plainly that Volta comes with no servers; the pictures show fictional
  servers on documentation addresses (RFC 5737) and no real app's name or icon.

## Counting characters

Play counts characters, not bytes:

```sh
python3 -c "import sys; print(len(sys.argv[1]))" "Volta — VPN-клиент"
```
