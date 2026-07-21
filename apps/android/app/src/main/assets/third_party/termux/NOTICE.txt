# Termux terminal-emulator fork

This module is based on the `terminal-emulator` library from
[`termux/termux-app`](https://github.com/termux/termux-app), pinned to tag
`v0.119.0-beta.3` at commit `e634d8f981f48b6b89202cf0e04533f0889e03b3`.

The terminal-view and terminal-emulator libraries are derived from Android
Terminal Emulator and are distributed under the Apache License, Version 2.0.
The full license text is included in `LICENSE`.

Yanami Next adds a transport-backed `TerminalSession` path so terminal input,
terminal-generated replies, mouse reports, and resize events can be carried by
the Komari WebSocket without spawning a local Android process.
