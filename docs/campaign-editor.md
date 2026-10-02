# Campaign Editor

The campaign editor builds on the map editor. A campaign is a list of levels; each level is an island, edited
with the map editor's terrain and resource brushes, plus the tribes, units, buildings, areas and triggers that
make it a story. Finished campaigns are played from **Campaign → New**, like the Viking and Native campaigns.

## Making a campaign

1. **Main menu → Campaign Editor.** Type a name and a description.
2. **Add level...** opens the island picker: choose the size, terrain and sliders, type a map code, or
   **Load...** a map saved in the map editor. **Add level** puts it at the end of the list.
3. Order the levels with **Move up** / **Move down**. They are played in this order; each opens once the one
   before it is won.
4. **Edit level** (or double-click) opens the level in the 3D editor.
5. **Save** writes the whole campaign to `campaigns/<name>.ttcampaign` in the game folder.

## Editing a level

The map editor's toolbar is at the top. The campaign bar is at the bottom:

- **Campaign** tool, with a dropdown of what to paint:
  - **Units** (peons, rock, iron and rubber warriors): left-drag paints them for the chosen player. Intensity sets
    how dense; at 0% each click places one. Right-drag removes that player's units of that kind.
  - **Chieftain**: one per tribe; placing it again moves it.
  - **Buildings** (quarters, armory, tower): left-click places one. Right-drag removes them.
  - **Towers with a rock, iron or rubber guard**: the guard is inside from the start, and stands on the tower in
    the editor too.
  - **Ship**: goes on open sea close to playable shore, clear of other ships. A level with ships plays with ships
    on any island.
  - **Golden statue**: belongs to no one, like the campaigns' treasures. Triggers can watch, place and take them.
  - **Areas**: left-click on open ground adds an area the size of the brush. Drag an area to move it, click it to
    rename or resize it, right-click it to delete it. Ctrl+Wheel over an area resizes it.
  - **Erase units and buildings**: removes everyone's objects under the brush.
- **Player** dropdown: who the units and buildings belong to, shown in their team colour.
- **Players...**: which of the six tribes take part, their tribe, team and how the computer runs them.
  Player 1 is always the one playing.
- **Triggers...**: the level's story and rules (see below).
- **Level...**: the title, the briefing shown before the level, and the starting objective.

Units, buildings and statues only go on playable ground (see **Playable area** in the toolbar) that is clear of
resources and other objects. Terrain edits that cut ground off the playable area take the objects there away
too; Ctrl+Z brings them back with the terrain.

Pointing at an area or object shows its name and which triggers use it on the hint line.

Picking for a trigger that wants a unit refuses a building or statue, and one that wants a tower or ship refuses
anything else, saying why on the hint line.

The **Esc menu** has **Save campaign**, **Test this level** (plays it at the difficulty you pick and comes back
to the editor afterwards), **Back to campaign levels** and **Exit**.

## Difficulty and the AI

Computer players are one of:

- **Opponent AI**: the skirmish AI, playing at the difficulty the campaign is played at (easy, normal or hard).
- **Passive**: roams, and only attacks when a trigger sends it.
- **Neutral**: stays where it is, like guards or captives.

As in the built-in campaigns, easy gives the player a hit bonus and hard gives it to the enemies. Each trigger
can also be limited to some difficulties, for example extra attack waves on hard only.

## Triggers

A trigger waits for its **condition** and then runs its **actions** in order. It can start switched off (to be
activated by another trigger) and can repeat. Conditions and actions that name an area or a unit or building
have a **Pick...** button: the windows step aside, you click it on the island, and they come back. The selected
trigger's areas and objects are marked in yellow on the island.

**Template...** starts a trigger from a recipe: opening story, win when every enemy is beaten, lose if something
is destroyed, attack waves, reinforcements, guards joining your side, an ally turning, freeing captives, losing
when the statues are gone, raiders carrying statues off, manning a tower or ship, and troops landing from a ship.
Pick its areas and units and it is done; the checks before testing list anything left unpicked.

Conditions: level starts, time passes, units enter an area, units come near a unit, building or statue, unit,
building or statue destroyed, player eliminated, all enemies defeated, player has fewer units than, supplies
gathered, chieftain casts a spell in an area, golden statues left (in an area or on the whole island), units in a
tower or ship.

Actions: show a dialog (with a Viking or Native portrait; later actions wait until it is closed), change the
objective, show a message, wait, move the camera to an area, spawn units, attack an area, attack a player, send
units out of the armory, refill the armory, replace lost units from the armory, hand over units in an area,
hand over a unit, remove a unit, building or statue, change a tribe's behaviour, activate or deactivate a trigger,
win the level, lose the level, and:

- **Change a tribe's team**: tribes on one team are friends. Guards can join the player, or an ally turn on them,
  at once.
- **A unit enters a tower or ship** and **Units in an area enter a tower or ship**: they walk in or aboard; both
  must be of one tribe, and a tower takes one warrior.
- **Units leave a tower or ship**: the guard comes down, or everyone aboard goes ashore (the ship must lie by land).
- **Send units out of a building**: quarters send peons, an armory or ship the kind chosen, a tower its guard.
  Units the building lacks are made while the tribe has room, so it always sends what it is told to.
- **Place golden statues in an area** and **Take the golden statues in an area** (or on the whole island).

Before a test, the level is checked: the player with no units, no enemy, no way to win, and every trigger setting
that is unpicked, deleted, of the wrong kind (a statue where a unit is wanted, quarters sending warriors) or names
a tribe not taking part.

A new level starts with one trigger: **All enemies defeated → Win the level**. The level is also lost when the
player's starting chieftain dies or the player has no units left.

## File format

`CampaignFile` writes a gzipped stream: magic `TTCP`, version, description, level titles, then for each level
the map exactly as a version 3 `.ttmap` body (`MapFile.writeBody`) followed by the `Scenario`. Scenario version 2
gave trigger steps a building beside their unit; version 1 levels still load. Saved campaign progress
keeps the campaign's name in `CampaignState`, so a campaign can be edited between plays; new levels open as the
ones before them are won.
