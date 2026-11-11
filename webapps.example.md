# webapps.json – short guide

`webapps.json` lists your sites. Every entry becomes its own app on the TV. JSON allows no comments,
so the explanations live here. Field reference: see the table in [README.md](README.md#config).

```sh
cp webapps.example.json webapps.json   # then edit, then:
mise run deploy
```

## Smallest possible file

```json
{
  "adb": "192.168.1.50:5555",
  "apps": [
    { "id": "news", "name": "News", "url": "https://news.example.com/" }
  ]
}
```

`adb` is the TV's address (TV settings → network, port is usually `5555`).

## Examples

**Own tile colour.** Without `banner`, the tile is generated: the name on a coloured background.

```json
{ "id": "news", "name": "News", "url": "https://news.example.com/", "color": "#8a1c1c" }
```

**Own banner image.** PNG, ideally 320x180. The path is relative to `webapps.json`; keep the images in
`banners/` (gitignored).

```json
{ "id": "news", "name": "News", "url": "https://news.example.com/", "banner": "banners/news.png" }
```

**Text too small or too big, or the site looks like a tablet.** `zoom` in percent, 50–300, works
like browser zoom. Pages see the TV as 960 CSS pixels wide, so many sites show their tablet layout.
Below 100 the page gets more room and picks its layout for that width: 75 → 1280 (laptop),
50 → 1920 (full HD monitor, small text from the couch). Above 100 everything gets bigger: 150 → 640.

```json
{ "id": "news", "name": "News", "url": "https://news.example.com/", "zoom": 150 }
```

**Site sends a bad mobile version.** `desktopMode` makes the app look like desktop Chrome on Linux.

```json
{ "id": "video", "name": "Video", "url": "https://video.example.com/", "desktopMode": true }
```

**Site says a stream is unencrypted (http) and cannot load.** An https page may not load http
content; `allowInsecureContent` allows it for this app only. Less secure: someone on the network
(Wi-Fi, provider) could change that content, scripts included. Leave it off where not needed.

```json
{ "id": "tv", "name": "TV", "url": "https://tv.example.com/", "allowInsecureContent": true }
```

**Jump between elements instead of moving a pointer.** With `"cursor": false` the app starts in
focus mode: the arrow keys jump from link to link and button to button, a yellow frame shows the
selected element. Hover menus and some custom players are not reachable this way; **hold OK** to
switch to the pointer (and back) at any time. `cursor` only sets the mode the app starts in.

```json
{ "id": "docs", "name": "Docs", "url": "https://docs.example.com/", "cursor": false }
```

**The same site twice**, e.g. two accounts: two entries with different `id`s. Each app has its own
cookies and logins.

```json
{ "id": "mailwork", "name": "Mail Work", "url": "https://mail.example.com/" },
{ "id": "mailhome", "name": "Mail Home", "url": "https://mail.example.com/" }
```

**Block ads by domain.** A top-level `blocklist` address of a domain list, one domain per line.
`mise run deploy` downloads it and builds it into every app; requests to listed domains and their
subdomains are not loaded. The [hagezi](https://github.com/hagezi/dns-blocklists) "Pro mini" list
(about 90,000 domains) is a good start:

```json
{
  "adb": "192.168.1.50:5555",
  "blocklist": "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.mini-onlydomains.txt",
  "apps": [
    { "id": "news", "name": "News", "url": "https://news.example.com/" },
    { "id": "video", "name": "Video", "url": "https://video.example.com/", "allowDomains": ["player.example.net"] },
    { "id": "shop", "name": "Shop", "url": "https://shop.example.com/", "blocklist": false }
  ]
}
```

The host of an app's own `url` is always allowed, with its subdomains, even if the list contains it.
If a video stops working, find the player's host (e.g. in the page source or a desktop browser's network tab) and add it to `allowDomains`
(domain only, no `https://` or path); it then counts with its subdomains. `"blocklist": false` turns
the list off for one app. The list only changes on the next `mise run deploy`.

## Good to know

- **`id` is the app's identity** (`local.tvwebapp.<id>`). Changing it creates a new app with empty
  logins; the old one stays on the TV. `name`, `url` and everything else can change freely.
- **`id` rules:** lowercase letters and digits, starting with a letter (`[a-z][a-z0-9]*`), no Java
  keywords such as `new` or `class`.
- **Remove an app:** delete the entry, run `mise run deploy`. Deploy lists the leftover app with the
  `adb uninstall` command; it never uninstalls on its own.
- **New banner not showing:** the launcher may cache it. Restart the launcher or the TV.
- **Mistakes:** the build stops and names the entry and field, e.g.
  `apps[1] (news).zoom: 400 nicht in 50–300`.
