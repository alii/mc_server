# mc_server

A Paper 26.2 survival server with a custom economy plugin. Everything needed to run the same setup is in `minecraft/`; only the world is your own.

- `compose.yml` — the server and 6-hourly backups (Docker, [itzg/minecraft-server](https://github.com/itzg/docker-minecraft-server)). `server.properties` is generated from its settings.
- `config/` — copied over the server folder on every start. Right now that's `bukkit.yml`, which raises the animal spawn cap (70) and spawns animals every 5 seconds instead of 20.
- `plugin/` — SmpPlugin: in-game money, a balanced server shop, a player market, and trading with real stock prices.
- Plugins from Modrinth: ViaVersion (lets newer clients join), Chunky (world pre-generation), LuckPerms (permissions).

## Run it

Needs Docker and Java 25+.

```sh
cd minecraft
(cd plugin && ./gradlew build)
cp .env.example .env        # put your Minecraft username in OPS and WHITELIST
docker compose up -d
```

The server is whitelisted: add friends with `docker exec mc rcon-cli whitelist add <name>`.

Optional, pre-generate the world so exploring doesn't lag:

```sh
docker exec mc rcon-cli chunky radius 3000
docker exec mc rcon-cli chunky start
```

## Update the plugin

```sh
(cd plugin && ./gradlew build) && docker compose restart mc
```

## Tests

```sh
cd minecraft/plugin && ./gradlew test
```

Integration tests run the real plugin inside [MockBukkit](https://github.com/MockBukkit/MockBukkit) (a fake Paper server with fake players and inventories). Stock prices come from canned Yahoo replies, so tests never touch the internet. Tests run on Java 25, same as the server; Gradle downloads it if missing.

## Commands

| Command | What it does |
|---|---|
| `/shop` | Server shop menu. Left click sells, right click buys, shift for more |
| `/sell [hand\|all]`, `/worth` | Quick selling, and checking a price |
| `/bal [player]`, `/pay <player> <amount>`, `/baltop` | Money basics |
| `/stock <TICKER>` | Live price |
| `/stock buy <TICKER> <shares\|$amount>` | Buy at the real price (fractional shares OK) |
| `/stock sell <TICKER> <shares\|all>` | Sell at the real price |
| `/portfolio [player]` | Holdings and profit/loss |
| `/market`, `/market sell <price>`, `/market mine` | Player item shop |
| `/eco give\|take\|set <player> <amount>` | Admin |

Prices and tuning are in the plugin's [`config.yml`](minecraft/plugin/src/main/resources/config.yml) and [`shop.yml`](minecraft/plugin/src/main/resources/shop.yml), written to `data/plugins/SmpPlugin/` on first start. Edit them there and run `/smp reload`; no restart needed. Code changes still need a restart.

## How the shop stays fair

- Money only comes from selling to the shop (plus $100 once on first join). It never turns back into items except by buying.
- Selling lowers that item's price for everyone. After $2,000 of one item is sold its price is halved, and that pressure halves every 24h. AFK farms stop paying much.
- Buying from the shop costs 3× the sell price, so nothing can be flipped for profit.
- Rare things (diamonds, netherite, boss drops) can be sold but never bought.
- Crafted and cooked things (glass, decor, food) can be bought but never sold, so nobody can craft cheap stuff into profit.
- Stock trades cost 1%, and leveraged ETFs and penny stocks are banned.
