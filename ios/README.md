# Volta для iPhone

Версия Volta для iOS: то же оформление, что у Android-приложения (медный диск, шрифты
Unbounded и Onest, темы Volta / Светлая / Океан / Уголь, нижняя панель вкладок), то же ядро
Xray и та же логика подписок и автовыбора сервера.

## Сначала главное: подпись и VPN

Apple не даёт устанавливать приложения в обход App Store без подписи, а VPN-расширению нужно
отдельное право — **Network Extensions (Packet Tunnel)**. Поэтому:

| Чем подписано | Установится | VPN заработает |
|---|---|---|
| Ничем (`…-ios-unsigned.ipa` как есть) | нет | нет |
| Бесплатный Apple ID (AltStore, Sideloadly и т.п.) | да, на 7 дней | **нет**: бесплатным аккаунтам Apple не выдаёт право на VPN-расширения |
| Платный аккаунт разработчика (Apple Developer Program, $99 в год) | да | **да** |
| TrollStore (только iOS, где он работает) | да | скорее всего да: сборка несёт свои права в ad-hoc-подписи, TrollStore их сохраняет |

Иначе говоря, без платного аккаунта разработчика Volta на iPhone откроется — можно
добавить подписку, посмотреть серверы и проверить их отклик, — но **VPN не подключится**.
Приложение так и скажет: «Эта копия Volta подписана без права на VPN».

## Где взять сборку

GitHub Actions (`.github/workflows/ios.yml`) собирает IPA при каждом изменении в `ios/`:

- **`volta-<коммит>-ios-unsigned.ipa`** — всегда. Внутри `Payload/Volta.app` и
  `Payload/Volta.app/PlugIns/PacketTunnel.appex`. Подписи разработчика нет, есть только
  ad-hoc-подпись с правами (VPN-расширение и App Group), чтобы программы переподписи и
  TrollStore знали, какие права нужны.
- **`volta-<коммит>-ios.ipa`** — только если в репозитории заданы секреты подписи (ниже).
  Это готовая к установке сборка.

Файлы лежат там же, где APK: тег `v…` — в этом релизе, любая ветка — в её тестовом
пре-релизе `apk-<ветка>` (для этой ветки — `apk-platform-ios`). Ещё они остаются артефактом
`volta-ios` у запуска workflow. `update.json` и APK workflow iOS не трогает.

## Подписанная сборка в CI

1. В [Apple Developer](https://developer.apple.com/account) заведите два идентификатора
   приложения, например `com.example.volta` и `com.example.volta.tunnel`, у обоих включите
   **Network Extensions** и **App Groups**, создайте группу `group.com.example.volta` и
   привяжите её к обоим.
2. Сделайте сертификат (Apple Development или Apple Distribution) и выгрузите его с ключом
   в `.p12`.
3. Сделайте два профиля (Development или Ad Hoc — с вашими устройствами), по одному на
   каждый идентификатор.
4. Добавьте секреты репозитория (Settings → Secrets and variables → Actions):

   | Секрет | Что это |
   |---|---|
   | `IOS_CERT_P12_BASE64` | `base64 -i cert.p12` |
   | `IOS_CERT_PASSWORD` | пароль от `.p12` |
   | `IOS_APP_PROFILE_BASE64` | `base64 -i app.mobileprovision` |
   | `IOS_EXT_PROFILE_BASE64` | `base64 -i tunnel.mobileprovision` |
   | `IOS_TEAM_ID` | Team ID из 10 символов (можно не задавать — возьмётся из профиля) |

Идентификаторы и группу workflow читает из профилей, так что они могут быть любыми.
Способ экспорта (development / ad hoc / enterprise / App Store) тоже определяется по профилю.

## Собрать самому

Нужны macOS, Xcode 16+, Go и [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
brew install xcodegen go
bash ios/scripts/build-libxray.sh v26.10.31 ios/Frameworks   # ядро Xray, несколько минут
cd ios && xcodegen generate && open Volta.xcodeproj
```

В Xcode выберите свою команду у целей Volta и PacketTunnel. Идентификаторы и группа задаются
настройками `VOLTA_BUNDLE_ID`, `VOLTA_EXT_BUNDLE_ID`, `VOLTA_APP_GROUP` в `project.yml`.

Тесты разбора ссылок, подписок, групп серверов и конфигов Xray — обычный Swift-пакет,
симулятор не нужен:

```sh
swift test --package-path ios
```

## Как устроено

```
ios/
  project.yml        проект для XcodeGen: приложение Volta и расширение PacketTunnel
  Package.swift      пакет VoltaCore для тестов (код из Shared/)
  Shared/            общий код без UIKit: разбор ссылок и подписок, группы Home/LTE/балансеры,
                     страны, ссылки volta:// (в т.ч. зашифрованные), автовыбор, конфиг Xray
  Bridge/            обёртка над LibXray (Invoke API v3)
  PacketTunnel/      NEPacketTunnelProvider
  Volta/             SwiftUI: тема, компоненты, экраны, модель
  Localization/      строки на 10 языках (генерирует tools/strings.py)
  Tests/             тесты VoltaCore
  scripts/           сборка LibXray, упаковка IPA, подпись, публикация в релизы
```

- **Ядро.** Xray-core через [libXray](https://github.com/XTLS/libXray) `v26.10.31`,
  собранный в CI его же скриптом через gomobile (`LibXray.xcframework`, только iOS и
  симулятор). Приложение использует его для проверки отклика (`pingBatch`), расширение — для
  туннеля.
- **Трафик из VPN-интерфейса в Xray** — два способа, переключаются в Настройках → Туннель:
  - «Основной»: [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) через
    [Tun2SocksKit](https://github.com/EbrahimTahernejad/Tun2SocksKit) отдаёт соединения в
    SOCKS-порт Xray, как на Android. Порт и пароль новые при каждом запуске, чтобы другие
    приложения на телефоне не могли им воспользоваться;
  - «Запасной»: TUN-вход самого Xray-core на дескрипторе utun расширения (`xray.tun.fd`).
- **Память.** Расширению iOS даёт около 50 МБ. Поэтому без geoip/geosite: локальные сети
  описаны диапазонами, «Российские сайты напрямую» — по доменам `.ru/.su/.рф` и списку
  сервисов. Куча Go ограничена libXray (30 МБ, сборка мусора каждую секунду), у hev
  уменьшены буферы.
- **Протоколы:** VLESS (в т.ч. REALITY, XTLS Vision, XHTTP, gRPC, WS, HTTPUpgrade), VMess,
  Trojan, Shadowsocks (включая 2022), SOCKS, HTTP, Hysteria2 (obfs salamander, смена портов),
  WireGuard (ссылки и `.conf`), а также серверы из JSON-конфигов Xray.
- **Подписки:** base64 и обычный текст, метаданные (`#profile-title`, `subscription-userinfo`,
  срок, трафик, объявления), JSON провайдера с группами, разметка `[H]/[L]/[A]` и
  `server-type`, скрытые и зашифрованные ссылки `volta://`, заголовки как у Android
  (`User-Agent: Volta/1.0 (iOS)`, `x-device-os`, `x-hwid` по желанию).
- **Подключение:** Авто Home+LTE (сначала проверка сети: открытый интернет или «белые
  списки»), Авто Home, Авто LTE, свой сервер. Если свежих замеров нет, перед подключением
  проверяется до 15 самых перспективных серверов группы, и выбирается быстрый; текущий
  сервер меняется, только если другой заметно быстрее.
- **Языки:** русский, английский, украинский, казахский, узбекский, турецкий, персидский,
  китайский (упрощённый), испанский, немецкий. iOS выбирает язык сам; в Настройках → Язык
  открывается страница Volta в настройках iPhone, где язык приложения можно поменять.

## Чего пока нет на iPhone

AmneziaWG и ключи AmneziaVPN, Hysteria v1, OpenFlux (на Android их ведут отдельные
программы-помощники, на iOS им нет места в расширении), наборы правил маршрутизации и файлы
geoip/geosite провайдера, выбор приложений для VPN, плитки и виджеты. Такие серверы
приложение распознаёт и честно говорит, что открыть их не может.
