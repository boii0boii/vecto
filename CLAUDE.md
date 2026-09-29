# Vecto: engineering context

Private founder and strategy notes live in `CLAUDE.local.md` (gitignored). Read both.

## Product rules
- MVP: **London, Uber (rides) + Uber Eats (food)**, Android 10+ phone app, Wear OS 3+ watch app.
- Entry points: home-screen widget, command bar (text/voice), watch (voice).
- No login, no user API keys: anonymous device ID; the backend pays for model calls.
- The agent **never pays**: it stops at Uber's confirm / Uber Eats' checkout. A confirm-then-place step
  (read the total from screen, user confirms in Vecto, agent taps the final button) is the planned next step.
- Memory lives on the phone. Only a compact summary is sent per command; the backend stores nothing.
- Out of scope unless agreed: more apps, login, iOS, message replies, a general any-app agent.

## Code map
- `android/`: Gradle project, AGP 9.1.0 (built-in Kotlin), Kotlin 2.4.20, Gradle 9.8.0.
  **compileSdk/targetSdk 36**, and library versions are pinned to ones that support SDK 36 (Compose BOM
  2026.06.01, core-ktx 1.18.0, okhttp 5.3.2, Wear Compose 1.6.2). Newer versions require SDK 37 / a newer
  Android Studio, so don't bump them casually.
  - `app/` (phone, package `ai.vecto`)
    - `widget/VectoWidget.kt`: Glance widget, opens CommandActivity (preset extra `"preset"`).
    - `command/CommandRunner.kt`: **the one pipeline** every command goes through (widget, command bar,
      watch): backend parse (with memory) → ActionRouter → memory update.
    - `command/CommandActivity.kt`: floating command bar. `command/IntentClient.kt`: POST `/v1/intent`.
    - `actions/ActionRouter.kt`: book_ride = Uber universal link; order_food = launch Uber Eats + playbook;
      remember = store a fact.
    - `agent/`: `Agent` (step runner, StateFlow state), `Playbooks` (scripted steps),
      `VectoAccessibilityService` (click/type helpers; `dumpScreen()` logs the tree under Logcat tag
      `VectoService`), `StatusOverlay` (TYPE_ACCESSIBILITY_OVERLAY pill with Stop).
    - `memory/MemoryStore.kt`: on-phone memory (`filesDir/memory.json`): history + outcomes, facts,
      derived frequent destinations / restaurants / last items. `summary()` is what gets sent.
    - `wear/WearCommandService.kt`: receives `/vecto/command` from the watch, runs CommandRunner,
      replies on `/vecto/status`. Background activity starts work because the accessibility service is
      system-bound, so watch commands require it to be enabled.
    - Debug `BACKEND_URL` = `http://10.0.2.2:8787` (emulator → Mac). Release URL is a placeholder.
  - `wear/` (watch, namespace `ai.vecto.wear`, **applicationId `ai.vecto` must match the phone** for the
    Data Layer): `MainActivity` opens straight into speech recognition and sends text to the nearest
    connected node; shows the phone's reply. Message paths are duplicated in `WearPaths` on both sides.
- `backend/`: Cloudflare Worker (TypeScript). `src/index.ts`: `POST /v1/intent` → Claude via
  `client.messages.parse` + zod structured output (effort low). Model from `MODEL` in `wrangler.toml`
  (`claude-opus-5`; a cheaper model is a product decision). KV rate limit 30/device/day when the
  `RATE_LIMIT` binding exists. With no `ANTHROPIC_API_KEY`, `mockParse()` handles "cab to X", "cab home",
  "order from X", "the usual from X", "remember X" for free local testing.
- **Contracts to keep in sync:** `ParsedCommand` (book_ride, order_food, remember, unsupported) and
  `MemorySummary` between `backend/src/index.ts` and `IntentClient.kt` / `MemoryStore.kt`; `WearPaths`
  between `app/` and `wear/`.

## Build and run
- `cd android && ./gradlew assembleDebug` builds `app-debug.apk` and `wear-debug.apk`.
- After editing Gradle files, **sync** in Android Studio (Cmd+Shift+O). A restart reuses cached models
  and shows "Please select Android SDK"; if a sync doesn't fix it, quit Studio and delete `android/.idea`.
- Backend: `cd backend && npm run dev` (port 8787); `npm run typecheck`.

## Status
- Phone + watch apps compile; neither has run on a device yet. Backend typechecks; mock parser verified
  with curl; the real Claude path hasn't been exercised yet.
- Uber Eats playbook labels ("Search", "Add…") are guesses; tune on a real device via the `VectoService`
  Logcat dump. The Uber deep link `dropoff[formatted_address]` needs a real-device check.

## Next steps
1. Run phone app on emulator; then phone + watch on real devices (same debug signing key).
2. Exercise the real Claude path once an API key is available.
3. Tune playbooks on a real phone; build confirm-then-place.
4. Analytics: widget_tap, command_sent (source: widget/bar/watch), flow_completed, flow_failed(step), stopped.
5. Play Integrity check in the backend (TODO in `index.ts`); Play Store closed test.
