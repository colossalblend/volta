# The texts of Volta for iOS. Russian is the source; plain words, no jargon.
# Placeholders: %@ for text, %d for numbers; every translation keeps the Russian ones.

LANGS = ["ru", "en"]

TABLE = {
    # ------------------------------------------------------------- common
    "common.clear": {"ru": "Очистить", "en": "Clear"},

    # ------------------------------------------------------------- connect
    "phase.probing": {"ru": "Проверяю сеть…", "en": "Checking the network…"},
    "phase.measuring": {"ru": "Ищу быстрый сервер…", "en": "Looking for a fast server…"},
    "phase.starting": {"ru": "Поднимаю туннель…", "en": "Starting the tunnel…"},
    "connect.error.noServer": {"ru": "Выберите сервер в списке", "en": "Choose a server in the list"},
    "connect.error.noServers": {"ru": "Сначала добавьте подписку", "en": "Add a subscription first"},
    "connect.error.noneDialable": {
        "ru": "Эта версия Volta не умеет открывать ни один из этих серверов",
        "en": "This version of Volta can't open any of these servers"},
    "connect.error.offline": {"ru": "Нет интернета. Попробуйте позже.", "en": "No internet. Try again later."},
    "connect.error.emptyGroup": {"ru": "В этой группе нет серверов", "en": "This group has no servers"},
    "connect.error.noAnswer": {"ru": "Серверы не ответили", "en": "The servers didn't answer"},
    "balancer.noAnswer": {"ru": "Серверы группы не ответили. Попробуйте позже.",
                          "en": "No server in the group answered. Try again later."},
    "balancer.picked": {"ru": "Выбран «%@» · %d мс", "en": "Picked “%@” · %d ms"},
    "ping.whileConnected": {
        "ru": "Пока VPN включён, отклик может быть чуть выше настоящего",
        "en": "While the VPN is on, the response time may read a little high"},

    # ------------------------------------------------------------- servers (core)
    "server.error.incomplete": {"ru": "В ссылке на сервер не хватает данных", "en": "The server link is incomplete"},
    "server.error.notInThisBuild": {"ru": "%@ пока не работает в Volta для iPhone",
                                    "en": "%@ doesn't work in Volta for iPhone yet"},
    "server.error.unknownType": {"ru": "«%@»: Volta не знает такой тип сервера",
                                 "en": "“%@”: Volta doesn't know this kind of server"},
    "server.error.typeNotSupported": {"ru": "«%@»: такой тип сервера Volta пока не поддерживает",
                                      "en": "“%@”: Volta doesn't support this kind of server yet"},
    "server.error.noKey": {"ru": "«%@»: в ссылке нет ключа доступа", "en": "“%@”: the link has no access key"},
    "server.error.noKeys": {"ru": "«%@»: в настройках сервера нет ключей", "en": "“%@”: the server settings have no keys"},
    "server.error.oldTransport": {
        "ru": "«%@»: сервер использует устаревший способ подключения, попросите у сервиса новую ссылку",
        "en": "“%@”: the server uses an outdated connection method, ask the service for a new link"},
    "server.error.encryption": {"ru": "«%@»: сервер использует шифрование, которое Volta не поддерживает",
                                "en": "“%@”: the server uses encryption Volta doesn't support"},
    "server.error.noRealityKey": {"ru": "«%@»: в ссылке не хватает ключа, попросите у сервиса новую",
                                  "en": "“%@”: the link is missing a key, ask the service for a new one"},
    "server.error.noPassword": {"ru": "«%@»: в ссылке нет пароля", "en": "“%@”: the link has no password"},

    # ------------------------------------------------------------- links
    "link.noAddress": {"ru": "В ссылке нет адреса подписки", "en": "The link has no subscription address"},
    "link.crypt.empty": {"ru": "Зашифрованная ссылка пустая", "en": "The encrypted link is empty"},
    "link.crypt.damaged": {"ru": "Зашифрованная ссылка повреждена", "en": "The encrypted link is damaged"},
    "link.crypt.cut": {"ru": "Зашифрованная ссылка обрезана", "en": "The encrypted link is cut short"},
    "link.crypt.newer": {"ru": "Ссылка зашифрована для более новой версии Volta — обновите приложение",
                         "en": "The link is encrypted for a newer Volta — update the app"},
    "link.crypt.unknownKey": {"ru": "Ссылка зашифрована ключом, которого нет в этой версии Volta",
                              "en": "The link is encrypted with a key this version of Volta doesn't have"},
    "link.crypt.failed": {"ru": "Не удалось расшифровать ссылку: она повреждена или не для Volta",
                          "en": "Couldn't decrypt the link: it is damaged or not meant for Volta"},
    "link.crypt.twice": {"ru": "Ссылка зашифрована дважды", "en": "The link is encrypted twice"},
    "link.crypt.noSubscription": {"ru": "В зашифрованной ссылке нет подписки",
                                  "en": "The encrypted link holds no subscription"},
    "link.crypt.noAddress": {"ru": "В зашифрованной ссылке нет адреса подписки",
                             "en": "The encrypted link has no subscription address"},

    # ------------------------------------------------------------- subscriptions (model)
    "sub.defaultName": {"ru": "Подписка", "en": "Subscription"},
    "sub.added": {"ru": "Подписка «%@» добавлена", "en": "Subscription “%@” added"},
    "sub.refreshed": {"ru": "Подписка обновлена", "en": "Subscription updated"},
    "sub.refreshedAll": {"ru": "Подписки обновлены", "en": "Subscriptions updated"},
    "sub.refreshFailed": {"ru": "Не обновилось подписок: %d", "en": "Subscriptions not updated: %d"},
    "sub.error.noLink": {"ru": "Вставьте ссылку на подписку", "en": "Paste a subscription link"},
    "sub.error.https": {"ru": "Используйте защищённую HTTPS-ссылку на подписку",
                        "en": "Use a secure HTTPS subscription link"},
    "sub.error.badLink": {"ru": "Это не похоже на ссылку", "en": "This doesn't look like a link"},
    "sub.error.noConnection": {"ru": "Нет связи с сервером подписки. Проверьте интернет.",
                               "en": "Can't reach the subscription server. Check the internet."},
    "sub.error.timeout": {"ru": "Сервер подписки не ответил вовремя.", "en": "The subscription server didn't answer in time."},
    "sub.error.tls": {"ru": "Не удалось установить защищённое соединение с сервером подписки.",
                      "en": "Couldn't set up a secure connection to the subscription server."},
    "sub.error.download": {"ru": "Не удалось загрузить подписку. Проверьте интернет.",
                           "en": "Couldn't download the subscription. Check the internet."},
    "sub.error.status": {"ru": "Сервис не отдал подписку (ошибка %d). Проверьте ссылку или срок подписки.",
                         "en": "The service didn't return the subscription (error %d). Check the link or the subscription term."},
    "sub.error.empty": {"ru": "Подписка пустая", "en": "The subscription is empty"},
    "sub.error.noServers": {
        "ru": "В подписке не нашлось ни одного сервера. Проверьте ссылку или напишите в поддержку VPN-сервиса.",
        "en": "No servers found in the subscription. Check the link or contact the VPN service."},
    "sub.error.onlyUnsupported": {"ru": "В подписке только серверы, которые Volta для iPhone пока не открывает: %@",
                                  "en": "The subscription only has servers Volta for iPhone can't open yet: %@"},

    # ------------------------------------------------------------- import (model)
    "import.error.amnezia": {"ru": "Ключи AmneziaVPN пока не работают в Volta для iPhone",
                             "en": "AmneziaVPN keys don't work in Volta for iPhone yet"},
    "import.error.nothing": {"ru": "Не нашлось ни одного сервера. Проверьте, что скопировали ссылку целиком.",
                             "en": "No servers found. Check that you copied the whole link."},
    "import.error.unsupported": {"ru": "Такие серверы Volta для iPhone пока не открывает: %@",
                                 "en": "Volta for iPhone can't open these servers yet: %@"},
    "import.already": {"ru": "Эти серверы уже есть в списке", "en": "These servers are already in the list"},
    "import.added.one": {"ru": "Добавлен %d сервер", "en": "Added %d server"},
    "import.added.few": {"ru": "Добавлено %d сервера"},
    "import.added.many": {"ru": "Добавлено %d серверов"},
    "import.added.other": {"ru": "Добавлено %d серверов", "en": "Added %d servers"},

    # ------------------------------------------------------------- tunnel
    "tunnel.error.noPlan": {"ru": "Нет настроек подключения. Включите VPN из приложения Volta.",
                            "en": "No connection settings. Turn the VPN on from the Volta app."},
    "tunnel.error.settings": {"ru": "iOS не приняла настройки VPN: %@", "en": "iOS didn't accept the VPN settings: %@"},
    "tunnel.error.noInterface": {"ru": "Не удалось подключиться к VPN-интерфейсу iOS",
                                 "en": "Couldn't attach to the iOS VPN interface"},
    "tunnel.error.core": {"ru": "Ядро Xray не запустилось: %@", "en": "The Xray core didn't start: %@"},
    "tunnel.error.bridge": {"ru": "Передача трафика остановилась (код %d)", "en": "Traffic forwarding stopped (code %d)"},
    "vpn.error.config": {"ru": "Профиль VPN испорчен. Удалите «Volta» в Настройках → VPN и подключитесь снова.",
                         "en": "The VPN profile is broken. Delete “Volta” in Settings → VPN and connect again."},
    "vpn.error.failed": {"ru": "VPN не подключился", "en": "The VPN didn't connect"},
    "vpn.error.stale": {"ru": "Профиль VPN устарел, попробуйте ещё раз", "en": "The VPN profile is outdated, try again"},
    "vpn.error.permission": {
        "ru": "iOS не дала создать VPN. Разрешите добавить конфигурацию VPN, когда iPhone спросит.",
        "en": "iOS didn't let Volta create the VPN. Allow adding the VPN configuration when the iPhone asks."},
    "vpn.error.entitlement": {
        "ru": "Эта копия Volta подписана без права на VPN. Так бывает с бесплатным Apple ID: VPN работает только в сборке, подписанной платным аккаунтом разработчика.",
        "en": "This copy of Volta is signed without the VPN permission. That happens with a free Apple ID: the VPN only works in a build signed with a paid developer account."},
}
