# easyeconomy

a server-side fabric economy mod for minecraft 26.3. geyser friendly by design (see below).

- player balances with /balance, /pay and admin /eco
- physical currency items with a small built-in resource pack
- a server shop you fully configure: items, prices, per-player buy limits
- player shops: list items for sale, buy and sell between players
- server-made challenges with coin rewards (advancements, obtaining items, and more)
- chest gui for everything, styled with vanilla item models and fonts

needs fabric loader and fabric api on the server only. clients need nothing;
the resource pack is optional and only changes how things look.

bedrock players joining through geyser get the same commands and guis
(a design assertion - the geyser section below says what that rests on).

## geyser and bedrock players

no extra setup is the design: everything in this mod is vanilla chest guis,
items and chat, which geyser translates for bedrock clients, so commands work
the same. this is a design assertion from using only vanilla ui elements -
verified against geyser's documented translation behavior, not on a live
bedrock client.

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

player-shop trades are journaled: every list, buy and cancel records its
intent (including both balances before a buy) and each durable step to
disk (fsync, atomic rename) before moving on. delivery only happens after
its journal mark is durable, so a refund can never meet an item that was
handed out. forced player-data saves are verified by reading the saved
file back - vanilla's save call swallows write failures and returns
normally, so the read-back is the only proof. a save that cannot be
verified before the other side exists aborts the trade; a save that
cannot be verified after delivery lets the payment stand, because
refunding against goods that may already be durable would duplicate
them - the player is warned and the log asks an admin to compensate. money moves
are durable-or-rolled-back: if the flush fails, the in-memory move is
reversed, so a live balance can never claim a payment the disk does not
have. recovery reads ground truth (the durable balances, the escrow
contents), keeps the journal until a refund is actually durable, and
escalates to the log when a seller can no longer cover one. the same
ordering holds for /deposit, /withdraw, /sell and the server shop:
removal durable before creation durable, each side verified, and the
few post-delivery windows above always loss-biased.

## performance

measured with `gradle perfCore` on this repo's harness (numbers from the
build machine, yours will differ):

- ~620k balance transfers/sec, ledger append included
- full account save with fsync: ~1.4 ms at 1,000 accounts, ~11 ms at 10,000
- ~4m currency splits/sec

challenge progress polls stats once per second per online player, a handful
of map lookups per challenge. by design the economy should not show up in
your tps; that is a design assertion from the workload shape, not a
measured guarantee.

## testing

every feature claim above is backed by a test you can run:

| claim | test |
| --- | --- |
| balances, /pay, /eco, limits, overflow safety | `gradle check`: 43 economy-core assertions, including an 8-thread transfer race proving money is conserved |
| crash-safe player shops | `gradle check`: 179 transaction assertions - a crash injected at every step of list/buy/cancel, restart + recovery, forced write failures, recovery crashing mid-refund, refund flush failure with same-process retry, seller-unable-to-cover refund, silent player-save failure, full inventory, unreadable-journal gate |
| currency forgery rejected | integration: anvil-renamed "coin" and tag-forged wrong-base item both rejected |
| shop, sell, limits, guis | integration: real chest-menu clicks through every flow |
| challenges pay out | integration: stat injection completes kill + obtain challenges; a reward earned at full balance goes pending and pays when there is room |
| state survives restart | integration verify phase: balances, buy limits, challenge progress, escrow |
| performance numbers | `gradle perfCore` re-runs them |

the integration suite is `tools/selftest.sh`: it boots a real server, joins
two fake players, runs 79 checks through the real command dispatcher and
real menus, restarts, and verifies persistence with 6 more. it is gated on
a jvm flag and never active in normal play.

## known limits

- crash recovery is loss-biased by design: a crash in a measured window
  can lose an item or coins, but the tested fault model has no path that
  duplicates either. the known windows: after a buy's delivery mark is
  durable and before the buyer's inventory save is verified (the paid
  item); after a listing's hand-clear is saved but before the listing
  reaches disk (the listed item); after a cancel removes the listing
  from escrow and before the returned item's save is verified (the
  returned item); after a shop buy or /withdraw whose inventory save
  cannot be verified, where the sale or withdrawal stands and a crash
  may lose the delivered goods. each window logs loudly for an admin to
  refund. these are the windows the suites exercise; they are not a
  proof that no other window exists.
- when an inventory save cannot be verified after a delivery, the code
  never guesses: the payment stands, because refunding against goods
  that may already be saved would duplicate them. admins compensate
  from the log instead.
- if a seller has spent the payment before recovery can refund it, the
  refund cannot be completed automatically: the journal is kept, trades
  are blocked, and the log asks for an admin.
- save verification reads the saved player file back and compares the
  inventory. if minecraft's file layout changes in a future version the
  verification may not match; the code then logs once and degrades to a
  plain forced save, so it is not unconditionally fail-closed and the
  guarantees above no longer hold in the degraded mode.
- the built-in pack server is off by default and serves only the one pack
  file, rate-limited. for anything serious, host the pack externally and
  put the url in server.properties; the config documents how.
- challenge progress polls stats once per second, so rewards land up to a
  second late. progress only counts what happens after a player is first
  seen; existing stats never complete a challenge.
- playtime challenges count minutes played since first join.
- storage is json files, single-server. no cross-server sync, no database.
- /withdraw refuses when the coins cannot fit in your main inventory;
  huge amounts in small denominations need many free slots. set
  denominations that fit your max balance.
- bedrock players use the same chest guis through geyser; there is no
  bedrock-native form ui. floodgate integration is logging only.
- admin mistakes are corrected with /eco; the ledger records every change.

license: mit
