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
- `/eco give|take|set <player> <amount>` - admin balance control
- `/easyeconomy reload` - reload configs

license: mit
