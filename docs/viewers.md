# Viewers

Coffer opens files itself, reading them from Telegram a piece at a time, without writing a
decrypted copy to the phone's storage. Anything it cannot show opens in another app from the
viewer's menu.

## Photos

Swipe through the photos of a folder. Pinch or double tap to zoom. HEIF, animated GIF and WebP
included.

## Videos

A video starts before it is downloaded and can seek anywhere.

- Double tap the sides to skip 10 seconds
- Swipe on the right for volume, on the left for brightness
- Hold to play at 2x
- Speed menu, repeat, a lock against touches, full screen

Each of these has a switch in Settings, Player.

## Music

The tracks of a folder play one after another, with the cover, title, artist and album from the
tags, previous and next, shuffle and repeat.

- Music plays on in the background, with its controls in a notification, on the lock screen and on
  headset buttons. When the vault locks, the track playing finishes and the rest of the queue is
  dropped, so nobody can play your other tracks from a locked phone. Other apps see only "Coffer"
  playing unless you turn on showing the track.
- Back folds the player into a card floating over the files.
- A call, another app's sound or unplugged headphones pause it.
- The progress bar is the song's waveform. The first time a song plays, Coffer downloads it whole to
  work the waveform out; after that it shows at once.
- By default the notification and lock screen say only Coffer. Settings, Player, **Show what is
  playing outside Coffer** shows the title and cover there.

## PDFs

Up to 256 MB, decrypted into memory. Pages are drawn as they scroll into view; pinch or double tap
to zoom; a page counter shows where you are. A password-protected PDF opens in another app.

## Text and Markdown

Notes, logs, config files, JSON, XML, code and subtitles up to 256 KB, with line numbers and syntax
colours. Markdown opens formatted, with headings, lists, checkboxes, tables and code blocks; the
`<>` button in the bar shows its source. Coffer asks before opening a link outside the app.

Settings, Text viewer has the font size, monospace, line wrap, line numbers, visible spaces,
colours and formatted Markdown.
