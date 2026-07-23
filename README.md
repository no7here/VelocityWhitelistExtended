### 📄 VelocityWhitelistExtended
A fork of TISUnion's [VelocityWhitelist](https://github.com/TISUnion/VelocityWhitelist), with improved performance, reliability and IP ban support.

> Tested on Velocity `4.1.0-SNAPSHOT` (Build 9).

***
#### 🪛 Changes
A list of what's changed versus the original plugin.
***
- Whitelist & blacklist can now both be active at the same time (blacklist takes priority)
- An IP ban system to block specific IP addresses from connecting at all (checked before either blacklist or whitelist)
- Configurable automatic blacklist that can blacklist their account when joining from a banned IP rather than just kicking them (check the config comments for more info)
- New default player-identification mode for new setups (existing setups are left untouched and work exactly as before) 
- Automatic upgrade system for configs to new format the first time you run the updated version (check migration section for more info)
- Reliability improvements, including a safer file management system to prevent corrupt or invalid files wiping your existing config on reload and a couple of rare race condition issues
- Small updates to modernise and clean up existing code

***
#### 🔄️ Migrating
If you're currently running TISUnion's original plugin, you can easily & safely switch to this fork while keeping your existing whitelist & blacklist.
***
1. **Backup your existing config** - Just like any changes, make a backup of `plugin/velocitywhitelist` folder somewhere safe so you can easily revert back.
2. **Stop your proxy** - Note that this is the proxy, not your Minecraft server. 
3. **Replace the plugin** - Delete / move the existing `VelocityWhitelist.jar` from your proxy's `plugins` folder and upload this fork's `VelocityWhitelist.jar` from [releases](https://github.com/no7here/VelocityWhitelist/releases/latest) in its place.
4. **Start your proxy again** - On first start, the plugin will automatically update your `config.yml` to the new format for you. Your existing whitelist, blacklist and settings (including whether you used names or UUIDs) are carried over unchanged.
5. **Try new features (optional)** - By default, only IP bans are enabled on migration. If you would like to enable automatic blacklisting, edit the config file and run `/velocitywhitelist reload`.

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
# Controls whether players should be automatically blacklisted when they try to join from a banned IP
# REQUIRES: identify_mode to be set to uuid & proxy to be in online mode, else it will be ignored.
blacklist_on_ipban_join: true
```

> [!WARNING]
> `blacklist_on_ipban_join` requires `identify_mode: uuid` **and** an online-mode proxy, else it will be ignored. 
> <hr>
> <details>
> <summary><i>Why does <code>blacklist_on_ipban_join</code> require an online proxy and UUID?</i></summary>
> <hr>
> <b>TL;DR:</b> Not doing so would allow an attacker to get any player name banned by connecting through a banned IP, including server administrators.
> <br><br>
> <b>TS;WM:</b> If either condition isn't true, player identities cannot be verified and an attacker can use a banned IP with an arbitrary player name and get it blacklisted. 
> <br><br>
> To prevent this, if the requirement isn't met, whether at first startup, after config migration or after any config reload, the plugin will force this option off in memory regardless of what's written in this file and print a warning explaining which requirement is missing.
> <br><br>
> This requirement means that to even try connecting to the proxy, an attacker must use <u>valid</u> Mojang accounts that have purchased Minecraft to attack, which makes it significantly more expensive and annoying to bypass versus the standard IP ban or account blacklist, or even a standard combination of them both.  
> <br><br>
> Ultimately, this feature is designed to act as a deterrence to would-be attackers / griefers and make a moderator's life easier. Just like anti-cheats, no anti-ban-bypass counter-measure is perfect. 
> </details>
> <hr>

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
- `/ipban list` - List all IPs on the IP ban list.
- `/ipban reload` - Reload IP ban file from disk. Doesn't reload config.

**Plugin Control:** 
- `/velocitywhitelist` - Show plugin information.
- `/velocitywhitelist reload` - Reload config, whitelist, blacklist & IP ban files.
