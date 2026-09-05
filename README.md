**English** | [Русский](README.ru.md) · branch **1.20.1** · [main (1.21.1)](https://github.com/Argorice/EpicYSM/tree/main)

<p align="center">
  <img src="https://raw.githubusercontent.com/Argorice/EpicYSM/1.20.1/branding/banner.png" alt="EpicYSM" width="100%">
</p>

# EpicYSM

Your [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) character fights with
[Epic Fight](https://github.com/Antikythera-Studios/epicfight) animations. Attacks, dodges,
guards, skills - all of Epic Fight's combat plays on the model you chose in YSM, with the
weapon in its hand, instead of on the default Epic Fight body.

Outside of Epic Fight's battle mode nothing changes: YSM keeps doing emotes, idle and walk
animations exactly as before.

- Minecraft 1.20.1, Forge 47+ (this branch; the `main` branch is 1.21.1 / NeoForge)
- Requires Epic Fight 20.14+ and Yes Steve Model 2.6+
- Client side only. Nothing to install on a server. Every player on screen is posed with
  their own model.

<p align="center">
  <img src="https://raw.githubusercontent.com/Argorice/EpicYSM/1.20.1/branding/scythe.gif" width="49%" alt="Scythe combo on an encrypted YSM model">
  <img src="https://raw.githubusercontent.com/Argorice/EpicYSM/1.20.1/branding/katana.gif" width="49%" alt="Dodge and slash with a long blade">
</p>

## How it works

There are two kinds of YSM models, and the mod handles both.

**Readable models** (a folder or a zip with `main.json` inside) are converted on the fly:
the geometry becomes an Epic Fight mesh, the bones become an Epic Fight skeleton with the
model's own proportions. Epic Fight then draws the model itself, so everything it can do -
weapons, armor, add-on effects, trails - works as on its own models.

**Encrypted models** (`.ysm` files) are never decrypted. Yes Steve Model keeps drawing them;
this mod reads the skeleton YSM has in memory, works out where every joint of Epic Fight's
body lands on that skeleton, and writes the pose into the bones a moment before YSM draws.
The weapon in hand is drawn by Epic Fight on the same joints, so Epic Fight's weapon
add-ons (Nightfall, Weapons of Miracles and others) work on encrypted models too.

Things that happen on their own:

- The model's own props that only show in special animations stay hidden.
- Swords, bows, sheaths a model was built with are put away in battle, so only the item
  actually in hand is visible. Names the mod does not recognise can be added to
  `config/epicysm/hidden-bones.txt`.
- Models with two bodies (a big and a small one, a human and a beast) are posed on whatever
  body is on screen, and the other one is left alone. When the model turns into something
  that is not a person, the mod steps back until it turns back.
- Drawing a bow or loading a crossbow with the use key plays YSM's own animation; attacks
  with the attack key are Epic Fight's.
- Yes Steve Model's start-up warning about Epic Fight is withdrawn, since this mod is the
  compatibility it warns about.
- When another mod takes over how a player looks - a transformation into a demon form, say -
  this mod steps aside for that player until the look is given back, and that mod's own
  player renderer is kept behind this one rather than replaced.
- Other players get the same treatment: whatever model YSM shows for them on your client is
  what fights, readable or encrypted, each with its own skeleton. A model switched
  mid-session is picked up on the spot.

## Settings

Mod list → EpicYSM → **Config**, the **Alt + O** key (rebindable under Controls → EpicYSM),
or `/epicysm config` in chat.

| Setting | What it does |
|---|---|
| Epic Fight animations on encrypted models | The whole encrypted-model half of the mod. Off: encrypted models fight with YSM's animations. |
| Held items on encrypted models | Whether Epic Fight or Yes Steve Model draws the weapon in hand. |
| Drawing a bow (use key) | YSM's animation or Epic Fight's while the use key is held on a bow. |
| Armor on readable models | Draw worn armor over a converted model. Off by default, so the model looks the way its author made it. |
| Hide the model's own weapons | For encrypted and for readable models separately. |
| Model's own animations in battle | How much of YSM's animation keeps playing under Epic Fight's. "Stop everywhere" behaves like a converted model. |
| Encrypted model, animations off | With the first setting off: keep the model, or keep Epic Fight's animations on a plain body. |
| Hair and cloth physics | Secondary motion on readable models. Off by default. |
| Diagnostics in the log | Detailed log lines and a description of every model in `config/epicysm/bones/`. Turn on when reporting a problem. |

Commands: `/epicysm list` shows the readable models that were found, `/epicysm reload`
re-reads models and `hidden-bones.txt` from disk.

## Per-model tweaks for readable models

An `epicysm.json` next to a model's `ysm.json` can correct the automatics:

```json
{
  "scale": 1.0,
  "translucent": false,
  "bones": {
    "Lantern": "hide",
    "Wings": "Torso",
    "Tail": "Root"
  },
  "pivots": {
    "Head": "Neck"
  },
  "physics": {
    "add": ["Hair", "Skirt"],
    "remove": ["Antenna"]
  }
}
```

`scale` multiplies the size the model is drawn at. `translucent` renders the model with
alpha blending (for models with transparent parts). `bones` maps a bone to an Epic Fight
joint by name, or hides it with `"hide"` - an entry here wins over the automatic prop
detection, so a bone the mod hides by mistake can be forced back. `pivots` takes a joint's
pivot from a different bone. `physics` adds bones to, or removes them from, secondary
motion (when physics is enabled in the settings).

## Known limits

- A player whose model this client does not have is shown with the default Epic Fight body.
  Yes Steve Model shares model choices through the server, so it needs YSM installed there
  (Epic Fight already requires that).
- Epic Fight hides the off-hand item while a two-handed weapon is held. The mod follows the
  same rule on encrypted models, so a shield disappears with a greatsword.
- Weapon renderers from add-ons that pose Epic Fight's armature themselves are shown on a
  stand-in armature shaped like the model. A renderer that throws is switched off for the
  session with one line in the log; nothing else is affected.
- A model whose body bones are named in a way the mod cannot read (no `Head`, `LeftArm`,
  `RightLeg` and so on) is left to YSM.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Epic Fight is pulled from the Modrinth maven; a jar placed in
`local/libs/` is used instead when present.

## Credits

EpicYSM is a bridge between two mods that do all the heavy lifting:

- **Yes Steve Model** by TartaricAcid and the YSM team - https://modrinth.com/mod/yes-steve-model
- **Epic Fight** by Yesman (Antikythera Studios) - https://github.com/Antikythera-Studios/epicfight

Model files belong to their authors. EpicYSM never decrypts protected `.ysm` models.

## License

MIT - see [LICENSE](LICENSE).
