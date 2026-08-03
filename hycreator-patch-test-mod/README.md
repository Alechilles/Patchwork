# Patchwork HyCreator Test Mod

This is a dependency-free Hytale asset-pack fixture for testing HyCreator's Patchwork authoring support. Open this folder as a writable mod workspace. The definitions live under `Server/Patchwork/Patches` and target only vanilla Hytale assets from release `0.5.7`.

Every file is a portable format-2 definition with its required `RequireFormat` sentinel. The fixture deliberately contains one example of every operation advertised by Patchwork's v2 capabilities document:

| File | Operation | Vanilla target | Expected change |
| --- | --- | --- | --- |
| `01-add-tag.json` | `Add` | `Ingredient_Bar_Iron` | Adds `Tags.PatchworkFixtureAdd`. |
| `02-merge-tags.json` | `Merge` | `Ingredient_Bar_Iron` | Merges `Tags.PatchworkFixtureMerge`. |
| `03-replace-craft-time.json` | `Replace` | `Ingredient_Bar_Iron` | Sets recipe craft time from 14 to 13 seconds. |
| `04-remove-drop-on-death.json` | `Remove` | `Ingredient_Bar_Iron` | Removes the explicit `DropOnDeath` flag. |
| `05-insert-salvage-output.json` | `Insert` | `Salvage_Armor_Wood_Hands` | Inserts tree sap after the stick output. |
| `06-replace-matching-salvage-output.json` | `ReplaceMatching` | `Salvage_Weapon_Axe_Crude` | Changes the fibre output quantity from 1 to 2. |
| `07-remove-matching-salvage-output.json` | `RemoveMatching` | `Salvage_Weapon_Blowgun_Tribal` | Removes the light-hide output. |
| `08-move-matching-salvage-output.json` | `MoveMatching` | `Salvage_Armor_Kweebec_Chest` | Moves sticks before fibre. |
| `09-macro-roundtrip.json` | `Macro` | `Ingredient_Bar_Iron` | Disabled opaque macro round-trip case. |

The macro fixture is intentionally disabled: macros are supplied by a host plugin, and this standalone fixture supplies no provider. HyCreator should preserve its unknown macro ID and option object without treating it as invalid. Enable it only in an environment that registers `HyCreatorFixture_UnknownMacro`.

To test the Patchwork runtime as well as authoring, install Patchwork alongside this mod. The eight non-macro definitions are enabled and can be applied against the stated release baseline; reset or remove the fixture after runtime testing because several examples deliberately alter recipes.
