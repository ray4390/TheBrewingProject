# RayCraft winemaking — first implementation slice

Status: **development branch only**, not production deployed. This work is built into
`ray4390/TheBrewingProject`; it is **not** another Minecraft plugin.

## Player experience

There is no experimental mode, recipe journal, extra workstation, custom crop, new
fermentation step, or required UI. Brew as usual in a heated TBP cauldron, transfer
the brew to an existing TBP barrel, then bottle it. Players keep their own recipes.

Classic wine (five sweet berries, five cauldron minutes, twenty TBP aging years)
still uses the original registered `wine` recipe. Sangria, brandy, other mixtures
and distillates still follow their existing recipe paths. Valid wine variations
use the existing `wine` ID so they remain recognizable as wine.

## Allowed amounts per cauldron recipe unit

- Sweet berries: **5–12**, required
- Glow berries: 0–3
- Apple: 0–2
- Honey bottle: 0–2
- Sugar: 0–4
- Melon slice: 0–3
- Cocoa beans: 0–2

Use **at most two different** optional ingredient types. Any unrelated item or
extra ingredient type makes a brew ineligible for the dynamic wine path.

## Deterministic formula

All numbers are openly documented, with no random formula rolls.

- Ideal brewing time (seconds): `300 + 20 × (berries−5) + 30 × honey + 15 × sugar + 20 × cocoa`
- Ideal aging (TBP aging years): `clamp(20 + 2 × (berries−5) + 2 × honey + 3 × cocoa − 3 × melon, 12, 45)`
- Alcohol added: `clamp(8 + floor((berries−5)/2) + honey + ceil(sugar/2) − min(melon,2), 5, 13)`
- Baseline toxins (bad / good / excellent): `6 / 3 / 1`
- Glow berries: reduce that bottle's toxin contribution by one; glow + melon reduces by two.
- Very sugary bad wine (4 sugar): one additional toxin.
- These changes **never remove toxins or alcohol already in a player**.

Times are expressed in the server's *configured* TBP cauldron minutes and aging
years; their real-world durations are defined by the existing TBP configuration.

## Small benefits, only for good/excellent wine

One shared 120-second cooldown applies to **all wine bonuses**, not to drinking or
to alcohol/toxin accumulation. No status effect or nourishment benefit for bad
wine. Benefits do not stack by chugging bottles.

- Apple: 1 hunger point
- Honey: 2 hunger points
- Melon: 1 hunger point
- Combined nourishment cap: 2 hunger points
- Apple + honey: 1 health point (half a heart)
- Sugar + cocoa: Speed I for **8 seconds**
- Glow berries + melon: further reduction of the *wine's own* added toxins
- Cocoa: changes the ideal aging formula

Speed never overwrites a stronger or longer existing Speed effect. The existing
TBP intoxication, nausea, vomiting, stumbling, pass-out and hangover systems
remain authoritative. Wine does not grant Night Vision, Fire Resistance, Haste,
Water Breathing, or any large combat benefit.

## Technical integration

- `core/.../wine/WineBalance`: validated quantities, predictable formula, and
  bounded quality-specific effects. Unit tested with JUnit.
- `bukkit-impl/.../wine/WineRecipeFactory`: dynamically builds an ordinary TBP
  `wine` recipe only for qualifying berry cooking/aging steps.
- `RecipeMatcherImpl`: includes that candidate in normal matching only when
  no explicit whitelist is in force. This never matches mixed or distilled steps.
- `BukkitRecipeResult`: stores minimal capped wine benefit values on the item.
- `PlayerEventListener`: permits only allowlisted additions and applies
  bounded benefits after a successful TBP drink-consumption event.
- `raycraft-winemaking-enabled: false`: default-off server rollout gate.
  This is an administrator deployment switch, **not a player-facing mode**.

## Deliberately deferred

This slice does **not** implement winery registrations, branding, secret-recipe
permissions, authenticated batch IDs, dedicated ChestShop matching, new barrel
species, wine vintage tracking, or new database migrations. Those require their
own integration tests and must not be represented as deployed.

## Release gate

Before setting `raycraft-winemaking-enabled: true`, qualify the exact production
Paper version in the disposable test environment, run the Gradle tests and Paper
26.3 harness, verify cauldron and barrel output, test classic red wine/sangria/
brandy regression, verify item sealing and persistence after restart, check
Java and Bedrock consumers, confirm ChestShop stock matching, and take a verified
backup. Do not edit production or restart Paper as part of this PR.
