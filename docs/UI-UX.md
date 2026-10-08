# Angel: interface direction and release checks

## Research and decisions

This is a focused review of published interfaces/guidance, not a hands-on usability study of competing apps.

- **Claude mobile:** the [Pratt critique](https://ixd.prattsi.org/2026/02/design-critique-claude-mobile-app/) describes a clear greeting → input hierarchy, grouped input controls, and ambiguity from duplicate controls and unexplained model names. Angel now uses a warmer landing screen, four task cards, a focused composer, and a shorter toolbar. Advanced workspace actions have named menu entries; readiness has text as well as color.
- **ChatGPT mobile:** the [published voice interface documentation](https://help.openai.com/en/articles/20001274-chatgpt-voice) illustrates a conversation-first interface with secondary actions grouped around the composer/navigation. Angel keeps its transcript central rather than adding a permanent dashboard or distracting bottom navigation. No unsupported voice or attachment affordances were added.
- **Material 3:** [color roles](https://m3.material.io/styles/color/the-color-system) and [typography guidance](https://m3.material.io/styles/typography/applying-type) emphasize role-based colors and readable hierarchy. A shared material component and theme tokens drive cards, prompts, tool activity, history, and the composer. Arbitrary accent colors are adjusted toward contrasting ink for at least 4.5:1 contrast against the highest surface and background; high-contrast accents target 7:1. Functional success/warning/error and terminal colors retain distinct meanings.
- **Apple materials:** [Liquid Glass guidance](https://developer.apple.com/design/human-interface-guidelines/materials) emphasizes supporting content and adapting for transparency/contrast needs. Angel's optional glass-inspired treatment uses translucent fills, fine borders, highlights, and modest shadows. Transcript text, command output, approvals, and code stay legible. High contrast falls back to opaque surfaces. There is deliberately no claim of true backdrop blur, refraction, or native Apple material rendering.

## Customization contract

- Changes are local to this phone and persist independently of agent configuration.
- System/light/dark mode; Mint/Iris/Ocean/Ember/Rose or arbitrary six-digit RGB accent.
- Tonal, solid, or glass surfaces; 60–96% glass opacity; 8–32 dp corners.
- 90–130% app text scale, multiplied by Android's system font scale.
- Optional ambient background, high contrast, and reduced decorative motion.
- Color entry requires **Apply**; switches/presets save immediately; sliders save on release. Reset requires confirmation and does not touch API keys, permissions, models, or conversations.
- Glass is lightweight Compose drawing without a blur dependency or API-level requirement. All effects support the existing Android 8+ target.
- System status/navigation icon colors follow the chosen app theme, even when it differs from the phone's appearance.

## Interaction improvements

- Phone readiness is visible in the header and setup checklist rather than encoded solely in a dot.
- Starter cards populate a draft; they never silently execute commands.
- Primary icon actions and clickable information/tool rows use at least 48 dp touch targets.
- Agent replies have an identity label and a larger copy target; user messages use accent-derived bubbles.
- When reading earlier messages, newly appended steps do not force-scroll the transcript. **Latest** gives a direct return path. This is not a complete redesign of streaming scroll behavior.
- The terminal deliberately retains its opaque, fixed high-legibility output canvas.

## Verification

Automated coverage:

- `ThemePreferencesTest`: hex validation, boundary/non-finite normalization, and light/dark contrast across presets and extreme colors.
- `ThemeStoreTest`: isolated on-device persistence/restore/reset; does not modify the user's appearance file.
- `AppearanceScreenTest`: theme/material selection, custom color application, high contrast, confirmed reset, dark-mode inherited text color, and screenshot capture in Compose, using an isolated in-memory theme.

Validation on 2026-10-08: debug app, test APKs, and the unsigned release APK build; all 43 JVM tests pass; Android lint completes with 0 errors, 42 warnings, and 2 hints (including existing dependency/resource/path warnings). Four targeted instrumentation tests pass through Android's direct runner on the connected Pixel 10 Pro Fold running Android 17. Reviewed dark-glass home, dark-glass appearance, light-tonal appearance, and a simulated 360 dp home at 130% app text size. Screenshot review caught and corrected inherited black text on transparent dark screens. This does not replace testing a physically folded device or the full device matrix.

The existing Espresso 3.5.1 dependency could not inject events on this Android version (`InputManager.getInstance` was removed). The test-only dependency is updated to 3.7.0, whose [release notes](https://developer.android.com/jetpack/androidx/releases/test) document the fix. Gradle's connected-test wrapper did not reliably report the Compose tests in this environment; the same packaged tests passed using the direct runner:

```sh
adb shell am instrument -w \
  -e class com.bruh.angel.ThemeStoreTest,com.bruh.angel.AppearanceScreenTest \
  com.bruh.angel.test/androidx.test.runner.AndroidJUnitRunner
```

Commands:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bruh.angel.ThemeStoreTest,com.bruh.angel.AppearanceScreenTest
```

## Release gate: manual checks still required

The refactor is not, by itself, a release certification. Before shipping:

- Check cover display, unfolded display, 320–360 dp devices, landscape, and keyboard open. Scroll the landing/settings screen at large system font sizes; ensure send/stop and Back remain reachable.
- Check system/light/dark × all materials, black/white accents, 60% glass opacity, high contrast, and reset after relaunch. Review screenshots for clipped labels and faint disabled controls.
- Run TalkBack through task cards, theme chips, sliders, switches, composer, copy, and approval sheets. Verify logical traversal, labels, minimum targets, and selection state. Check system animation-scale behavior alongside the app's reduce-motion option.
- Exercise conversation history, Markdown/code/tables, live tool output, approval editing, cancellation, model settings with unsaved edits, MCP, and local model screens. Confirm unchanged data/security semantics.
- Profile scrolling and glass cards on an older supported phone. Check long conversations and large tool output without relying only on a flagship Fold.
- Run Android lint, the full instrumented suite, a signed release build, and packaging/store/privacy checks. Review the existing release optimization configuration independently of this UI change.
