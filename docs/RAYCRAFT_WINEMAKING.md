# RayCraft Winemaking

**Implementation:** embedded directly into RayCraft's customized TheBrewingProject. No other plugin, player toggle, automatic recipe journal, custom grapes, or extra fermentation station is introduced.

**Development state:** this design lives on the `feature/raycraft-winemaking-v1` branch. The feature is `raycraft-winemaking-enabled: false` by default. Do **not** call it production deployed until the exact live Paper/ChestShop release has passed smoke tests and the native integration is activated.

## Brewing wine

Use an ordinary heated TBP cauldron, with at least **five sweet berries** and no more than **two types** of additional ingredient. Extract and age it in an existing TBP barrel. Brews are classified from the ingredients and timing—there is no special command or mode.

| Vanilla ingredient | Per-brew limit | Gameplay purpose |
| --- | ---: | --- |
| Sweet berries | **5–12** (required) | Base alcohol and maturity |
| Glow berries | 0–3 | Reduces wine-contributed toxins by 1 |
| Apple | 0–2 | +1 hunger point; with honey, half-heart restoration |
| Honey bottle | 0–2 | +2 hunger (capped), raises alcohol potential |
| Sugar | 0–4 | Raises potential alcohol; with cocoa beans gives brief Speed I |
| Melon slice | 0–3 | Lighter wine (lower alcohol), +1 hunger point |
| Cocoa beans | 0–2 | Changes maturation target; with sugar gives brief Speed I |

Only properly brewed good or excellent wine grants hunger, healing or Speed. The maximum per serving is **2 hunger points**, **1 health point (half a heart)**, or **Speed I for 8 seconds**. Special benefits are subject to one **120-second per-player shared cooldown**; drinking alcohol is *never* blocked by the cooldown. Alcohol and toxin changes accumulate in TBP as normal.

Glow berries and melon reduce **toxins contributed by that bottle**. They do not cure the drinker's existing poisoning, intoxication, addiction, nausea or hangover. Even exceptional wine remains intoxicating. The existing non-alcoholic hangover remedy stays separate.

## Published brewing formulas

These are exact deterministic targets, not hidden combinations or a randomized jackpot.

```text
Ideal cauldron seconds = 300 + 20×(sweet_berries−5) + 30×honey + 15×sugar + 20×cocoa
Ideal barrel age-years = clamp(20 + 2×(sweet_berries−5) + 2×honey + 3×cocoa − 3×melon, 12, 45)
Wine alcohol added = clamp(8 + floor((sweet_berries−5)/2) + honey + ceil(sugar/2) − min(melon,2), 5, 13)
Toxins added = max(0, qualityBaseline − (glow>0?1:0) − (glow>0 AND melon>0?1:0)
                    + (quality BAD AND sugar==4 ? 1 : 0))
Quality baseline toxins: BAD=6, GOOD=3, EXCELLENT=1.
```

TBP's configured cauldron-minute and barrel-aging-year durations are authoritative. Ordinary five-berry red wine still follows the original **5 cooking minutes + 20 age-years** registered recipe. The barrel-cooking quality score is TBP's existing deterministic proximity-based score—small timing errors lower quality gradually. More aging never increases the alcohol score.

Example: seven sweet berries, one apple, one honey -> **6 minutes 10 seconds** cauldron target, **26 TBP age-years**, **10 alcohol**. If good/excellent, also restores up to two hunger points and half a heart when not on cooldown.

### Existing drinks

The untouched TBP recipe registry still handles red wine (five berries), sangria, brandy, whiskey, rum, cocktails, and all registered mixing/distillation procedures. A dynamic candidate is built only for unmodified normal berry `cook + age` steps. Its recipe identity stays `wine` so classic recipes referencing `brewery:wine` can recognize it.

## Wineries (optional)

A player can make wine without joining a winery. A winery adds collaborative branding, not economic buffs or a recipe unlock. All of these are normal commands:

```
/winery create Westbridge Cellars
/winery info
/winery members
/winery invite PlayerName
/winery accept
/winery kick PlayerName
/winery leave
/winery transfer PlayerName
/winery color blue
/winery register Westbridge Reserve
/winery bottle Westbridge Reserve
/winery code Westbridge Reserve
/winery help
```

An owner creates a winery, chooses its color, invites members, transfers ownership, and registers a named signature wine. Invites expire after seven days. Members may bottle registered products but cannot change the underlying definition. An owner must transfer ownership before leaving. Each player belongs to at most one winery.

To register a signature wine, **hold an unsealed, properly aged, good-or-better TBP bottle**, and use `/winery register <name>`. TBP records only an **opaque SHA-256 fingerprint** of that registered wine's ingredients, cooking time bucket, aging target and barrel type—not an automatically browsable journal. Players must record their own recipes. Later production with the same signature formula can be labeled using `/winery bottle <name>`, before TBP sealing. The registered provenance is cryptographically signed using a secret kept only in `plugins/TheBrewingProject/wineries.yml`. Normal TBP sealing (paper/crafting table or `/tbp seal`) must preserve a *verified* label. Anvil-renamed imitations have no valid signature.

`wineries.yml` is persistent server data and must be included in backups. Do not copy its signing key into public documentation. Losing the secret invalidates verification of previously labeled bottles, so a corrupted file causes startup of the winery registry to **fail closed**, not silently replace the key.

## ChestShop

The pre-existing **RayBrewShops** adapter is being absorbed *into TBP*; no third-party plugin is bundled. TBP's native ChestShop integration supports:

- `B_wine` and `B_<recipe>`: generic, recipe-wide shops for ordinary unlabeled brews of any quality.
- `W_XXXXXXXX`: a winery's registered wine, and **only a bottle with a valid signed winery identity**.
- Existing prices, funds, permission checks, stock capacity and item transfer still belong to ChestShop, not TBP.
- Neither unsigned impostors nor lower-quality unrelated bottles can satisfy a registered-brand shop.

A signature wine code appears using `/winery code <registered name>`. After retiring the original adapter, `/brewshop` remains available natively from TBP and displays the current held bottle's code.

### Migration safety

**Never activate competing generic-shop listeners.** While the existing `RayBrewShops.jar` remains present, the native TBP adapter is limited to *new signed W-codes*; the old adapter keeps existing B-code shops. Once native B/W-code and stock matching are verified against the exact ChestShop 3.13-pre-1 release, back up shops and configs, retire the old standalone jar **at a controlled restart**, and confirm that native ChestShop matching replaces it. Native generic B-code shops exclude registered brand bottles, avoiding accidental sale of premium stock as ordinary wine.

Do not remove the existing integration before validating that the new integration starts and all current shop signs still work.

## Release testing and rollback

Before any production deployment:

1. `./gradlew clean build --no-daemon` passes, including new WineBalance and WineryRegistry unit tests.
2. Exact Paper 26.3 server lifecycle + optional integration fixture tests pass.
3. Gameplay test **normal cauldron cooking**, correct target time, barrel aging, extraction, signed branding, and paper/`tbp seal` re-label persistence.
4. Test failures: unregistered/too many ingredients, premature aging, too-old wine, wrong formula for brand, unsigned/renamed bottle, brand signature corruption, deletion or corruption of winery registry.
5. Verify exact `B_` and `W_` sign parse, purchase and sale, full and partial transactions, stock counters, rollback and ChestShop transactions with the real 3.13-pre-1 jar.
6. Regression-check existing five-berry wine, brandy and sangria, plus non-wine beers/spirits and Bedrock Geyser consumption.
7. Preserve `recipes.yml`, `config.yml`, `wineries.yml`, ChestShop shop data and prior TBP jar as a verified backup before controlled deployment.
8. Production feature stays off on initial staged restart. Enable only after a measured acceptance test, and verify again. Retain the original ChestShop integration until the replacement is confirmed.

**Known limitation:** mock JVM tests and Paper lifecycle tests cannot simulate a genuine player's physical interactions with a live barrel/shop. Those checks must be performed in the disposable world before claiming gameplay-tested readiness.
