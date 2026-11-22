# roadmap

ideas queued for after v1.0.0, roughly in priority order. none of this is
built yet.

## v1.1 candidates

- `/baltop` - balance leaderboard, cached and paged
- player-shop listing expiry - listings return to the seller after a
  configurable number of days, using the same journaled delivery path
- optional sales tax on player-shop trades, configurable percent, burned
  or routed to a server account
- more challenge types: distance traveled, xp earned, deaths, raids won;
  challenge categories in the gui
- sell limits for the server shop (per-player, per window, same machinery
  as buy limits)
- `/eco history <player>` - recent ledger entries for one player, for
  admin audits
- sqlite storage backend for large servers, keeping the json default

## maybe later

- bedrock-native forms for shop/challenges through floodgate + cumulus,
  with the chest gui as fallback
- documented external pack hosting recipes (s3, cloudflare, nginx)
- offline player-shop delivery to a claim chest instead of dropping at
  the seller's feet
