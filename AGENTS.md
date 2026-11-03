# Empathy Star Android

- This repository is the complete Android prototype source, not a production release.
- Android application code and resources belong in `app/src/`; the independent structural test application belongs in `device-probe/`; reusable verification scripts belong in `tools/`.
- Read `README.md`, `docs/ARCHITECTURE.md` and `docs/STATUS.md` before modifying behavior.
- Keep app adapters separate from business logic. Never expose Android accessibility nodes to model or card code.
- Read only explicitly authorized foreground chat applications. Unknown windows, missing authorization, locked devices and stale origins must fail closed.
- Never send chat messages automatically, overwrite drafts, bypass protected screens or enable speech as a reading dependency.
- Chat content and model results stay in memory. Never commit credentials, local settings, screenshots, window dumps, device logs, signatures or build products.
- Before any model request, verify provider, model, price and explicit user authorization. SiliconFlow allows only currently verified-free models and forbids all DeepSeek models; no paid fallback. Offline tests must not call models.
- Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (Windows: `./gradlew.bat` from an ASCII-only checkout path), then `tools/Verify-ReaderBoundary.ps1` after code changes. Report device validation separately from build success.
- Docs belong in `docs/`; generated outputs, SDKs and caches remain ignored. Do not delete user files or change credentials, system settings or publication visibility without authorization.
