# android-tv-pwa

Turns websites into Android TV apps: one APK per site, each with its own launcher tile, banner, name,
cookies and logins. Each app shows the site full screen in a WebView, with a remote-control pointer
and video fullscreen.

## Requirements

- [mise](https://mise.jdx.dev) (installs Java 17 and the Android SDK from `mise.toml`)
- `jq`, and a TV with ADB debugging over the network enabled

```sh
mise install
```

## Config

Your sites live in `webapps.json` (gitignored, as are `banners/` and `blocklist.txt`). Start from the example:

```sh
cp webapps.example.json webapps.json
```

| Field | Required | Default | Meaning |
|---|---|---|---|
| `adb` (top level) | for deploy | – | TV address, `host:port` |
| `blocklist` (top level) | no | – | http(s) address of a domain list; requests to listed domains are not loaded (see [Ads](#ads)) |
| `id` | yes | – | `[a-z][a-z0-9]*`, unique; package becomes `local.tvwebapp.<id>` |
| `name` | yes | – | Label of the tile |
| `url` | yes | – | Start page (http/https) |
| `banner` | no | generated | PNG, 320x180 recommended, path relative to the config |
| `color` | no | `#1e1e2e` | Background of the generated banner and the loading screen (without it, the loading screen follows the theme) |
| `cursor` | no | `true` | Start mode: `true` pointer, `false` jump from element to element; hold OK to switch |
| `desktopMode` | no | `false` | Desktop user agent and wide viewport |
| `allowInsecureContent` | no | `false` | Let the https site load http content (e.g. streams); less secure |
| `zoom` | no | `100` | Page zoom in percent (50–300), like browser zoom: below 100 the page gets more room (50 = 1920 px wide) |
| `blocklist` | no | on | Only `false` allowed: this app does not use the domain list |
| `allowDomains` | no | `[]` | Domains (with their subdomains) this app never blocks through the list, e.g. a video host |

The build checks the config and names the entry and field of every error.
Examples and tips: [webapps.example.md](webapps.example.md).

## Build and install

```sh
mise run deploy            # all apps
mise run deploy foo bar    # only these ids
```

Deploy never uninstalls anything. If the signing key changed, it prints the `adb uninstall` command
(this deletes the app's logins).

Build without a private config: `mise exec -- ./gradlew -Pwebapps=webapps.example.json assembleDebug`

## Remote control

Two modes; **hold OK** (0.6 s) to switch between them at any time:

- **Pointer:** arrows move the pointer (faster while held), OK clicks, pushing against an edge scrolls.
  The pointer hides when a video with sound starts, the next arrow brings it back.
- **Focus:** arrows jump from element to element, a yellow frame shows the selected one, OK clicks it.
  Text fields open the keyboard only on OK, not when the frame lands on them.
  On a video player (a video in the page, or one embedded from another site): the arrows move on,
  OK plays it in fullscreen. In fullscreen ▲/▼ set the TV volume, ◀/▶ seek 10 s, OK plays/pauses.
  Embedded players seek with their own keys, if they have any.

BACK leaves video fullscreen, then goes back in history, then closes the app.

While the first page loads, the app shows its banner with a spinner on its `color`. When a later page
takes longer than 0.4 s, a thin yellow bar at the top shows the progress.

Focus frame, loading screen, bar and pointer use Catppuccin colours: Mocha when the TV is set to dark,
Latte when it is set to light (read at app start).

The installed builds are not debuggable (much faster on the TV), but pages can still be inspected from
the Mac with `chrome://inspect` (WebView debugging is on).

## Ads

New windows open in the app itself, but only after a click, and never from a player embedded from
another site (their clicks open ads). A page that sends itself to another site without a key press
is stopped ("Weiterleitung blockiert"); links, forms and server redirects still work.

**Domain list.** With a top-level `"blocklist"` address (e.g. a
[hagezi](https://github.com/hagezi/dns-blocklists) list), every app skips requests to listed
domains and their subdomains: pages, iframes, images, scripts, XHR/fetch, media and service workers
get an empty 403. A page change to a listed domain does not happen ("Werbung blockiert").
Only whole domains, no element or path rules (uBlock syntax).

- The list is fetched only by `mise run deploy`: it downloads into a temp file, needs at least
  1000 domains, then replaces `blocklist.txt` in the repo root (first line `# source: <address>`).
  Gradle builds this copy into every app; the TV never downloads it.
- Download failed: deploy keeps the old copy with a warning if it came from the same address,
  otherwise it stops. A plain Gradle build without the copy stops with
  `blocklist.txt fehlt: erst mise run deploy ausführen`.
- The host of the app's own `url` is always allowed, with subdomains (the build warns if it is listed).
  A video broken by the list: add its host to `allowDomains`, or turn the list off for that app
  with `"blocklist": false`.
- The app loads the list in the background at start. Until it is ready (at most 2 s) page changes
  are not checked. Log line (tag `tvwebapp`): `Blockliste: <N> Domains in <X> ms` or
  `Blockliste: nicht geladen (<reason>)`.

## Test page

`testpage/index.html` checks cookies, popups, user agent, zoom, text input and scrolling:

```sh
python3 -m http.server 8000 -d testpage
adb reverse tcp:8000 tcp:8000      # then add an app with "url": "http://localhost:8000/"
```
