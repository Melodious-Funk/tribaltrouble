# Campaign Editor

The campaign editor builds on the map editor. A campaign is a list of levels; each level is an island, edited
with the map editor's terrain and resource brushes, plus the tribes, units, buildings, areas and triggers that
make it a story. Finished campaigns are played from **Campaign → New**, like the Viking and Native campaigns.

## Making a campaign

1. **Main menu → Campaign → Campaign Editor** (top right). Type a name and a description.
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

The map editor's **Spawns** tool (see [Spawns](#spawns)) works on a level too: a tribe's spawn is where its camera
starts and where its AI calls home, in place of its quarters or chieftain.

Units, buildings and statues only go on playable ground (see **Playable area** in the toolbar) that is clear of
resources and other objects. Terrain edits that cut ground off the playable area take the objects there away
too; Ctrl+Z brings them back with the terrain.

Pointing at an area or object shows its name and which triggers use it on the hint line.

Picking for a trigger that wants a unit refuses a building or statue, and one that wants a tower or ship refuses
anything else, saying why on the hint line.

The **Esc menu** has **Save campaign**, **Save island as map...** (a copy of the level's island, terrain and
resources, in your `maps` folder; the campaign itself is left unsaved), **Test this level** (plays it at the
difficulty you pick and comes back to the editor afterwards), **Back to campaign levels** and **Exit**.

## Editing together

A campaign can be edited with other players, like a map in a shared map editing session. Everyone needs a
multiplayer profile.

- **Host:** in the campaign editor, **Esc menu → Edit together** shares the campaign you are editing. From the
  **Shared sessions...** window on the multiplayer menu's Maps tab, **Host session...** opens the island picker:
  tick **Campaign session** to start a new campaign on the island, or press **Host a saved campaign...** to share one
  you saved.
- **Join:** campaign sessions show as *Campaign* in the **Kind** column of the **Shared sessions...** window. Joining
  hands you the whole campaign, opened on the level being edited.

Everyone edits the same level. Terrain, resources, units, buildings, areas, triggers, the players' setup and the level's
texts reach the others a few times a second. Each player's new objects, areas and triggers get ids of their own, so
triggers never mix them up. Undo takes back your own edits only. If a window like **Players...** or **Level...** is
open when someone else changes the same thing, OK only writes what you changed in it.

**Esc menu → Campaign levels...** lists the levels. **Go to level** takes everyone to another level, and
**Add level...** picks an island and takes everyone to it as a new level. The level you leave stays in the campaign
as it is. Removing and reordering levels, and the campaign's description, stay in the campaign editor's level list.

**Save campaign** writes your own copy to your `campaigns` folder; a player who joined is asked for a name the first
time. **Test this level** takes you out of the session.

Press **Enter** to chat with everyone in the session. Messages also show on the info lines, and chat commands such
as `/ignore` work as in a game. Map editing sessions have the same chat.

## Spawns

In the map editor and the campaign editor, the **Spawns** tool (on the toolbar's third row, with a dropdown of
players) picks where each player starts:

- Left-click puts the chosen player's spawn there, on playable ground or the sea; a player has one spawn, so
  clicking again moves it. Right-click takes away the spawn under the cursor.
- Each spawn shows on the island as a double ring in the player's colour with the player's number, floating on the
  sea where it lies in it, and crossed out in red if a terrain edit leaves it on neither. Undo and redo take spawns back and put them back, and in a
  shared session everyone sees them move.
- Map previews (the map browser, the multiplayer menu's shared maps and lobby, campaign levels) show each spawn as a
  numbered disc in the player's default colour.

Playing a map with spawns from **Custom map...** (skirmish or hosting a multiplayer game) first asks where players
start:

- **Spawns, in order**: the player in lobby slot 1 starts at spawn 1, and so on. Players whose slot has no spawn
  take the spawns no one has, in order.
- **Spawns, shuffled**: the players take the spawns in an order of chance, the same for everyone in the game.
- **Random places**: the spawns are ignored and the game picks places as on any island.

Players left over when the spawns run out start at the game's own places, as far from the spawns as it finds. In a
multiplayer lobby, **Info** shows the choice under **Players start**. Each player's units start by their spawn with
room for quarters and an armory, or at the nearest such room; on an archipelago, their ships wait in the nearest deep
water.

A spawn in the sea starts its player's units aboard ships at the nearest deep water to it when the game has ships
(the **Ships** advanced setting, or an archipelago), and on the nearest walkable shore when it does not. A spawn in an
inland lake always starts them on its shore.

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
the map exactly as a `.ttmap` body (`MapFile.writeBody`) followed by the `Scenario`. Campaign version 1 keeps the
maps as version 3 bodies; version 2 keeps them as version 5 bodies, which hold the level's spawns.

`.ttmap` version 5 adds the spawns after the description: a count, then a player slot byte and the grid cell as two
shorts per spawn. A map without spawns is still written as version 4, so older builds and servers read it; the
matchmaking server reads version 5 headers (`MapFileHeader`) from this change on. Scenario version 2
gave trigger steps a building beside their unit; version 1 levels still load. Saved campaign progress
keeps the campaign's name in `CampaignState`, so a campaign can be edited between plays; new levels open as the
ones before them are won.
