# Telemetry

BitChord-X sends anonymous diagnostics to its own server so crashes and bugs can be found and fixed. It is on by
default, the first run explains it, and Settings lets anyone change how much is sent. The phone and the PC behave the
same.

## The three levels

| | Full (default) | Minimal | Off |
|---|---|---|---|
| That the app is open, with its version and platform | yes | yes | no |
| ID it is sent under | the install's own stable ID | an ID that changes every UTC day | none |
| Can the server follow one install from one day to the next | yes | no | not applicable |
| Error reports | type, message and stack trace (scrubbed), device model, system version, CPU | the error type alone | none |

Never sent at any level: the account, the IP address (it is read from the request for rate limiting and is not stored),
the listening history or the playlists.

Full is what makes bugs fixable: a stack trace and the device it happened on are usually what it takes to reproduce a
crash. Minimal keeps the headcount and a count of errors by type, which tells us that something is failing and how
often, but not where. Turning it down asks for a second confirmation, explains why the reports matter and offers Minimal before Off. The wording describes what a report is and what it is for; it does not hide the options or make the choice harder than one tap.

## What the client does

- **Full.** Sends the install ID, as before. The ID is derived from the Android ID (hashed, so the raw one never leaves
  the phone) or, on the PC, generated once and stored. Error reports carry the message and stack trace with the home
  folder, the user name and URL queries removed.
- **Minimal.** Derives `UUID(installId, UTC day)` and sends that instead: the same device is the same ID for a day and
  a different one the next, and the stable ID never leaves the device. An error report has the platform, the version
  and the type, with `level: "minimal"`, and no ID, message, stack trace or device fields.
- **Off.** Opens no connection and sends nothing. Switching to Off while the app is open takes it off the live count
  at once.

## What Minimal can and cannot tell us

The daily ID does not create a new "user" in the database: Minimal never writes to `installs`, so there is no row to
multiply. What it writes is a counter per day. That has a consequence worth knowing:

- It answers: how many devices opened the app on a given day (daily active devices), on which version and platform, and
  how often each error type happens.
- It cannot answer: how many different people used the app over a week or a month, how many are new or returning,
  retention, or which version an install upgraded from. Adding up a week of Minimal gives device-days, not people, so the
  overview calls it `deviceDays` and never mixes it into the unique-install figures that Full produces.
- A person who switches between levels is counted as one install while on Full and as device-days while on Minimal.
  The two are shown apart and are not merged.

That is the price of not being able to follow anyone, and it is deliberate.

## What the server does

The server does not trust the client to have left things out. A request that says `level: "minimal"` is handled by a
separate path that reads only what a minimal report is allowed to carry, whatever else the body contains.

- **Presence** (`POST /api/presence` and the `/ws/presence` hello). A minimal ping still counts toward the live
  open-apps number, which is in memory. It is **not** written to `installs` or `connection_events`. Instead the daily ID
  is checked against an in-memory set for the current UTC day: the first time it is seen, `minimal_usage` gets
  `devices + 1` for `(day, platform, version)`. The set is dropped when the day changes and is never persisted, so a
  restart can count a device a second time that day. That is the whole cost of not keeping IDs.
- **Errors** (`POST /api/telemetry/error`). A minimal report needs only `errorType`. The server validates it against
  `^[A-Za-z0-9_.:-]{1,64}$` (anything else is stored as `other`, so the field cannot carry a message, a path or an
  ID), and increments `minimal_errors` for `(day, platform, version, type)`. The ID, message, stack trace and device
  fields of such a body are ignored.
- **Full** requests are handled exactly as before.
- An old client that sends no `level` is Full.

### Tables

```
minimal_usage  (day, platform, app_version, devices)                  primary key (day, platform, app_version)
minimal_errors (day, platform, app_version, error_type, count)        primary key (day, platform, app_version, error_type)
```

Neither has an install ID, a timestamp finer than the day, or any free text beyond the validated type. They are tiny,
so the dashboard's history prune keeps a year of them, against 30 days for the full tables.

### Reading it

`GET /api/admin/overview` on the private dashboard port now includes a `minimal` object next to the full figures:
`deviceDays` (the sum of devices per day over the range) and `errors` (the ten most frequent types). The dashboard page
does not draw it yet.

## Debian Linux (.deb) builds

Linux installations from `.deb` packages (tagged with `packageFormat: "deb"`) are excluded from user and installation metrics:

- **Not counted as users or installs**: They are never written to the `installs` table and are excluded from `minimal_usage` device tallies, total installs, active 24h, returning, retention, and install directory views.
- **Diagnostics preserved**: Technical errors (`error_reports` and `minimal_errors`) and connection heartbeats (`connection_events` and real-time live concurrency) are still received and recorded so package-specific crashes, issues, and network reachability remain visible for debugging without tracking user adoption.

## Where it is enforced

- `shared/.../data/telemetry/TelemetryLevel.kt`: the levels, and the daily ID.
- `shared/.../data/telemetry/TelemetryReporter.kt` and `.../presence/Presence.kt`: what each level sends, including optional `packageFormat`.
- `desktopApp/.../DesktopPackageFormat.kt` and `DesktopPresence.kt`: packaging detection on Linux.
- `backend/telemetry/store.go` and `backend/main.go`: exclusion of deb packages from install tallies, preserving error and connection telemetry.
- Tests: `TelemetryLevelTest`, `TelemetryScrubTest`, `PresenceTest`, `DesktopPackageFormatTest`, `backend/telemetry/level_test.go`, `TestMinimalTelemetry` and `TestDebianTelemetry`.
