# easyeconomy

a server-side fabric economy mod for minecraft 26.3. geyser friendly.

- player balances with /balance, /pay and admin /eco
- physical currency items with a small built-in resource pack
- a server shop you fully configure: items, prices, per-player buy limits
- player shops: list items for sale, buy and sell between players
- server-made challenges with coin rewards (advancements, obtaining items, and more)
- chest gui for everything, styled with vanilla item models and fonts

needs fabric loader and fabric api on the server only. clients need nothing;
the resource pack is optional and only changes how things look.

works for bedrock players joining through geyser: same commands, same guis.

## geyser and bedrock players

no extra setup: everything in this mod is vanilla chest guis, items and chat,
which geyser already translates for bedrock clients. commands work the same.

if you also run floodgate on the server, easyeconomy notices it (soft
dependency, nothing else is used) and logs when a bedrock player joins, so
admins can tell platforms apart in the log. floodgate is optional; without it
the mod behaves identically and simply skips that log line.

## setup

drop the jar into `mods/` next to fabric api, start the server once, then edit
`config/easyeconomy/config.json`. shop items live in `config/easyeconomy/shop.json`,
challenges in `config/easyeconomy/challenges.json`.

## commands

- `/balance` (alias `/bal`) - your balance; admins can check others
- `/pay <player> <amount>` - pay another player
- `/shop` - open the server shop
- `/sell` - sell the item in your hand to the server
- `/challenges` - open the challenges gui
- `/withdraw <amount>` - turn balance into currency items
- `/deposit [all]` - turn currency items in hand (or inventory) into balance
- `/playershop` (alias `/ps`) - browse and buy player listings
- `/ps sell <price>` - list the item in your hand for sale
- `/eco give|take|set <player> <amount>` - admin balance control
- `/easyeconomy reload` - reload configs

## crash safety

player-shop trades are journaled: every list, buy and cancel writes its
intent and each durable step to disk (fsync, atomic rename) before moving
on, and forces a player-data save after every hand change or delivery so a
crash cannot roll inventories back into a dupe. a failed write aborts the
trade and undoes anything already done, in memory and on disk. on startup
the journal is replayed: interrupted payments are refunded, half-finished
deliveries are reported and dropped - recovery never creates money or
items, the worst case across any crash point is one lost item. the same
rule holds for /deposit and /sell: items leave your hand before money
arrives, never both at once.

## performance

measured with `gradle perfCore` on this repo's harness (numbers from the
build machine, yours will differ):

- ~620k balance transfers/sec, ledger append included
- full account save with fsync: ~1.4 ms at 1,000 accounts, ~11 ms at 10,000
- ~4m currency splits/sec

challenge progress polls stats once per second per online player, a handful
of map lookups per challenge. the economy will not show up in your tps.

## testing

every feature claim above is backed by a test you can run:

| claim | test |
| --- | --- |
| balances, /pay, /eco, limits, overflow safety | `gradle check`: 43 economy-core assertions, including an 8-thread transfer race proving money is conserved |
| crash-safe player shops | `gradle check`: 156 transaction assertions - a crash injected at every step of list/buy/cancel, restart + recovery, forced write failures, recovery crashing mid-refund, unreadable-journal gate |
| currency forgery rejected | integration: anvil-renamed "coin" and tag-forged wrong-base item both rejected |
| shop, sell, limits, guis | integration: real chest-menu clicks through every flow |
| challenges pay out | integration: stat injection completes kill + obtain challenges; a reward earned at full balance goes pending and pays when there is room |
| state survives restart | integration verify phase: balances, buy limits, challenge progress, escrow |
| performance numbers | `gradle perfCore` re-runs them |

the integration suite is `tools/selftest.sh`: it boots a real server, joins
two fake players, runs 36 checks through the real command dispatcher and
real menus, restarts, and verifies persistence with 6 more. it is gated on
a jvm flag and never active in normal play.

## known limits

- crash recovery is loss-biased by design: the worst case at any crash
  point is one lost item, reported in the log for an admin to refund.
  nothing is ever duplicated.
- the built-in pack server is off by default and serves only the one pack
  file, rate-limited. for anything serious, host the pack externally and
  put the url in server.properties; the config documents how.
- challenge progress polls stats once per second, so rewards land up to a
  second late. progress only counts what happens after a player is first
  seen; existing stats never complete a challenge.
- playtime challenges count minutes played since first join.
- storage is json files, single-server. no cross-server sync, no database.
- /withdraw of a huge amount in small denominations drops many item
  entities; set denominations that fit your max balance.
- bedrock players use the same chest guis through geyser; there is no
  bedrock-native form ui. floodgate integration is logging only.
- admin mistakes are corrected with /eco; the ledger records every change.

license: mit
