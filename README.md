### 📄 VelocityWhitelist
A fork of TISUnion's [VelocityWhitelist](https://github.com/TISUnion/VelocityWhitelist), with IP ban support.</p>

***
#### 🪛 Changes
A list of what's changed versus the original plugin.
***
- Updated logic so whitelist & blacklist can be enabled at the same time (with blacklist taking priority)
- Implement IP bans (takes priority over blacklist) 
- Updated configuration file to support IP bans
- Small updates to modernise and clean up existing code

***
#### 📄 Files
All the files generated and used by the plugin. 
***

#### ⚙️ Config
`plugins/velocitywhitelist/config.yml`

```yaml
# Config file version. Do not edit it.
version: 2

# Control how players are identified
# Options: name, uuid. Default: uuid
# For online servers, it's recommended to use UUID as it tracks the permanent ID of the player's Mojang account, rather than the changeable username
identify_mode: uuid

# Control if the whitelist is enabled
whitelist_enabled: true
# Message sent to players who aren't whitelisted
whitelist_kick_message: You are not in the whitelist!

# Control if the blacklist is enabled (takes priority over whitelist if both are enabled)
blacklist_enabled: true
# Message sent to players who are blacklisted
blacklist_kick_message: You are banned from the server!

# Control if IP bans are enabled
ipban_enabled: true
# Message sent to players who join on a banned IP address
ipban_kick_message: Your IP address is banned from the server!
# Controls whether players should be automatically blacklisted when they try join on a banned IP
blacklist_on_ipban_join: true
```

#### 📁 Whitelist & Blacklist
`plugins/velocitywhitelist/whitelist.yml` // `plugins/velocitywhitelist/blacklist.yml`

```yml
names:
  - Player1
  - Player2
uuids:
  - 12345678-1234-1234-1234-123456789abc
```

#### 🌐 IP Bans
`plugins/velocitywhitelist/ipbans.yml`
```yml
ips:
  - 192.168.1.1
  - 10.0.0.5
```

***
#### 🔨 Commands
Requires the permission `velocitywhitelist.command`.
***

> [!NOTE]
> Only `name` can be used in name mode. Either can be used in UUID mode. 

**Aliases:** 
- `/vwhitelist`
- `/vblacklist`
- `/vipban`

**Whitelist & Blacklist (Substitute `/whitelist` with `/blacklist`):**
- `/whitelist` - Show plugin status.
- `/whitelist <add / remove> <name / uuid>` - Add or remove a player to the whitelist.
- `/whitelist list` - List all players on whitelist.
- `/whitelist reload` - Reload whitelist file from disk. Doesn't reload config.

**IP Bans:**
- `/ipban` - Show plugin status.
- `/ipban <add / remove> <ip>` - Ban or unban an IP address.
- `/ipban list` - List all IPs on the blacklist.
- `/ipban reload` - Reload IP ban file from disk. Doesn't reload config.

**Plugin Control:** 
- `/velocitywhitelist` - Show plugin information.
- `/velocitywhitelist reload` - Reload config, whitelist, blacklist & IP ban files.

