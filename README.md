# MW MineBot

Client-side Minecraft 1.8.9 Forge mod that does the mining for you in Mega
Walls. It has two modes. The mining bot gathers resources. The tunnel bot digs
a staircase down to bedrock and then runs a corridor along it.

The bot plays through ordinary input: key presses, mouse buttons and camera
turns, the same as a hand on the keyboard. It sends no packets of its own.

## Features

### Mining bot

- **Finds and mines**: stone, logs, dirt, iron ore, coal, iron blocks and
  chests. Each can be switched on or off
- **Plain stone only.** Granite, diorite and andesite are skipped as targets,
  but anything standing in the way is still dug through
- **Walks to what it wants**, digging through whatever the next step runs into.
  It climbs ledges and pillars up with blocks from the hotbar to reach things
  overhead
- **Chests that appear where you dug are taken next**, ahead of everything
  else. If you had already moved on by the time one showed up, it walks back
  for it
- **Protected chests are left alone.** The server's "This chest is protected"
  message is recognised
- **Picks the right tool** from the hotbar before each block
- Stops by itself when there is nothing left to mine

### Tunnel bot

- Digs a staircase straight down, one block forward per block down, with
  headroom
- On reaching bedrock it switches to a 2-high corridor and turns at every
  bedrock corner
- Starts in whichever direction you are facing

### Blocks that come back

Some blocks, such as the map boundary, break on your screen and are then put
straight back by the server. A block that comes back twice is treated as
unbreakable and never touched again. A neighbouring block of the same wall only
gets one chance. If the tunnel bot finds itself boxed in by these, it stops.

### Walls Fall timer

Reads the sidebar countdown and plays a sound at 1:30, 1:00 and 0:30. The bots
stop by themselves at 0:01, because it is time to fight.

## Usage

**Right Ctrl** opens the menu. You can rebind it in Controls under
"useful MW".

| Button | What it does |
| --- | --- |
| Mining bot / Tunnel bot | Start or stop. Only one runs at a time |
| Stone, Logs, Dirt, Iron ore, Coal, Iron blocks, Chests | What the mining bot goes for |
| Turn speed | How long each camera turn takes |
| Randomise | Varies turn speed, aim point and pauses between blocks |
| 2 block height | Mine only at foot and head height |
| Click | *per block* lets go of the button during camera turns; *hold* keeps it down |
| Walls Fall | Countdown alert on or off |
| Climb ledges / Pillar up | How the mining bot gets up to things |
| Show target | Draws a box around the block being mined |
| Debug log | Extra detail in `latest.log` |

While the menu is open the bots are paused. Opening anything else, such as
chat or the inventory, stops them.

Settings are saved to `config/mwminebot.properties`.

## ⚠️ Limitations ⚠️

- **Use at your own risk.** Many servers, Hypixel included, forbid automation,
  and using this can get your account banned
- **Does not run on minemen.club.** The mod refuses to start there
- **It uses your real keyboard and mouse** while the game window has focus.
  Keep your hands off while it runs. Switching away from the window releases
  everything
- **A returning block has to show up within 4 seconds** of being dug to be
  noticed. With lag worse than that, a wall block can get dug more than twice.
  The same 4 second window applies to chests appearing where you dug
- The tunnel bot does not go after chests

## Download

[Download MW MineBot](https://github.com/Haiseth/mw-minebot/releases)

- Minecraft 1.8.9
- Forge
