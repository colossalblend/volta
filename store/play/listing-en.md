# Google Play: listing (English)

Play Console → "Main store listing". Limits: name 30 characters, short description 80,
full description 4000. How to count them: `store/play/README.md`.

## App name

Volta — VPN client

## Short description

Paste your VPN service's subscription and tap the disc. Volta picks the server

## Full description

Volta is a VPN client for subscriptions from VPN services. Paste the link your service gave you and tap the disc: Volta checks the servers and connects to the one that answers fastest.

Volta has no servers of its own. You need a subscription or a key from a VPN service.

Picks and changes the server by itself
• Volta splits servers into two groups. Home is for Wi-Fi and the open internet. LTE is for mobile internet where the carrier lets through only approved sites.
• In Home+LTE mode Volta checks which network you are on and takes a server from the group that works there. Move from Wi-Fi to mobile data or back, and it checks the network again right away.
• While the VPN is on and the screen is on, Volta checks the server every two minutes. If it stops answering or gets much slower, Volta moves to another one.
• You can also pick a server yourself; Volta then leaves it alone.

Subscriptions, links and keys
• Subscription links, including hidden and encrypted ones: their address is never shown, and the subscription still updates as usual.
• Server markup from your VPN service: which servers are for Wi-Fi and which for mobile networks.
• Single servers: VLESS (with REALITY), VMess, Trojan, Shadowsocks, SOCKS, Hysteria and Hysteria2.
• AmneziaVPN keys, AmneziaWG and WireGuard config files (.conf), OpenFlux links.
• All of it goes into one field: Volta works out what you pasted.
• Bluetooth sharing: send a subscription or a server from your phone to a TV or another phone nearby. The link travels encrypted, and only after you match a six-digit code on both screens.

Apps and sites
• Split tunnelling: let chosen apps bypass the VPN, or send only those apps through it.
• Ready-made app sets (banks, government services, marketplaces, delivery and more) turn on with one tap. A set only picks apps that are on the phone.
• Routing: which sites open through the VPN and which go direct. Rules from your subscription apply on their own; you can add your own.
• An ad and tracker filter: AdGuard DNS plus Volta's own list. Modes: Ads and trackers, Strict (also cookie banners, personalisation and A/B testing) and Family. Sign-in buttons, video and links in emails keep working.
• Torrent blocking: many servers ban users for seeding.

Close at hand
• Quick Settings tiles for Home+LTE, Home and LTE, app shortcuts and a home screen widget.
• Live speed and traffic through the VPN: today, this month and per day for two weeks.
• Internet check: in airplane mode, with no network or only a weak mobile signal, Volta tells you so instead of trying to connect in vain.
• A guide to turning on Android's always-on VPN and blocking connections without VPN.
• Move to a new phone: subscriptions, your own servers, rules and settings in one file.
• Simple mode: one big button for anyone who doesn't need settings.
• Dark themes, a light one and Material You (Android 12 and newer), 15 languages.
• Works on Android TV with a remote.

Privacy
Volta has no accounts, ads or analytics. Subscriptions and settings stay on the phone. Your traffic goes to your VPN service's servers; Volta does not log it or send it anywhere. Details: https://colossalblend.github.io/volta/privacy.html

Volta is also available for Windows and iPhone: builds and source code on GitHub.

Guide (in Russian): https://colossalblend.github.io/volta/guide.html
Source code: https://github.com/colossalblend/volta
