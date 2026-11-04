# The texts of Volta for iOS. Russian is the source; plain words, no jargon.
# Placeholders: %@ for text, %d for numbers, in the same order in every language.
#
# T(key, ru, en, uk, kk, uz, tr, fa, zh-Hans, es, de)
# P(key, ru=(one, few, many), en=(one, other), uk=(one, few, many), then (one, other)
#   for kk, uz, tr, fa, zh-Hans, es, de)

LANGS = ["ru", "en", "uk", "kk", "uz", "tr", "fa", "zh-Hans", "es", "de"]
TABLE = {}


def T(key, *texts):
    assert len(texts) == len(LANGS), f"{key}: {len(texts)} texts"
    assert key not in TABLE, f"duplicate {key}"
    TABLE[key] = dict(zip(LANGS, texts))


def P(key, ru, en, uk, kk, uz, tr, fa, zh, es, de):
    pairs = {"en": en, "kk": kk, "uz": uz, "tr": tr, "fa": fa, "zh-Hans": zh, "es": es, "de": de}
    one = {"ru": ru[0], "uk": uk[0]}
    other = {"ru": ru[2], "uk": uk[2]}
    for lang, forms in pairs.items():
        one[lang] = forms[0]
        other[lang] = forms[1]
    TABLE[f"{key}.one"] = one
    TABLE[f"{key}.few"] = {"ru": ru[1], "uk": uk[1]}
    TABLE[f"{key}.many"] = {"ru": ru[2], "uk": uk[2]}
    TABLE[f"{key}.other"] = other


# ------------------------------------------------------------------ tabs and common
T("tab.home", "Главная", "Home", "Головна", "Басты бет", "Bosh sahifa", "Ana sayfa", "خانه", "首页", "Inicio", "Start")
T("tab.servers", "Серверы", "Servers", "Сервери", "Серверлер", "Serverlar", "Sunucular", "سرورها", "服务器", "Servidores", "Server")
T("tab.subscriptions", "Подписки", "Subscriptions", "Підписки", "Жазылымдар", "Obunalar", "Abonelikler", "اشتراک‌ها", "订阅", "Suscripciones", "Abos")
T("tab.settings", "Настройки", "Settings", "Налаштування", "Баптаулар", "Sozlamalar", "Ayarlar", "تنظیمات", "设置", "Ajustes", "Einstellungen")
T("common.add", "Добавить", "Add", "Додати", "Қосу", "Qo‘shish", "Ekle", "افزودن", "添加", "Añadir", "Hinzufügen")
T("common.back", "Назад", "Back", "Назад", "Артқа", "Orqaga", "Geri", "بازگشت", "返回", "Atrás", "Zurück")
T("common.cancel", "Отмена", "Cancel", "Скасувати", "Бас тарту", "Bekor qilish", "Vazgeç", "انصراف", "取消", "Cancelar", "Abbrechen")
T("common.clear", "Очистить", "Clear", "Очистити", "Тазалау", "Tozalash", "Temizle", "پاک کردن", "清除", "Borrar", "Leeren")
T("country.other", "Другие", "Other", "Інші", "Басқалар", "Boshqalar", "Diğer", "سایر", "其他", "Otros", "Andere")

# ------------------------------------------------------------------ units
T("unit.ms", "%d мс", "%d ms", "%d мс", "%d мс", "%d ms", "%d ms", "%d میلی‌ثانیه", "%d 毫秒", "%d ms", "%d ms")
T("unit.b", "%@ Б", "%@ B", "%@ Б", "%@ Б", "%@ B", "%@ B", "%@ بایت", "%@ B", "%@ B", "%@ B")
T("unit.kb", "%@ КБ", "%@ KB", "%@ КБ", "%@ КБ", "%@ KB", "%@ KB", "%@ کیلوبایت", "%@ KB", "%@ KB", "%@ KB")
T("unit.mb", "%@ МБ", "%@ MB", "%@ МБ", "%@ МБ", "%@ MB", "%@ MB", "%@ مگابایت", "%@ MB", "%@ MB", "%@ MB")
T("unit.gb", "%@ ГБ", "%@ GB", "%@ ГБ", "%@ ГБ", "%@ GB", "%@ GB", "%@ گیگابایت", "%@ GB", "%@ GB", "%@ GB")
T("unit.tb", "%@ ТБ", "%@ TB", "%@ ТБ", "%@ ТБ", "%@ TB", "%@ TB", "%@ ترابایت", "%@ TB", "%@ TB", "%@ TB")
T("unit.bps", "%@ Б/с", "%@ B/s", "%@ Б/с", "%@ Б/с", "%@ B/s", "%@ B/sn", "%@ بایت/ث", "%@ B/s", "%@ B/s", "%@ B/s")
T("unit.kbps", "%@ КБ/с", "%@ KB/s", "%@ КБ/с", "%@ КБ/с", "%@ KB/s", "%@ KB/sn", "%@ کیلوبایت/ث", "%@ KB/s", "%@ KB/s", "%@ KB/s")
T("unit.mbps", "%@ МБ/с", "%@ MB/s", "%@ МБ/с", "%@ МБ/с", "%@ MB/s", "%@ MB/sn", "%@ مگابایت/ث", "%@ MB/s", "%@ MB/s", "%@ MB/s")

P("servers.count",
  ("%d сервер", "%d сервера", "%d серверов"),
  ("%d server", "%d servers"),
  ("%d сервер", "%d сервери", "%d серверів"),
  ("%d сервер", "%d сервер"),
  ("%d ta server", "%d ta server"),
  ("%d sunucu", "%d sunucu"),
  ("%d سرور", "%d سرور"),
  ("%d 个服务器", "%d 个服务器"),
  ("%d servidor", "%d servidores"),
  ("%d Server", "%d Server"))

# ------------------------------------------------------------------ home
T("home.state.off", "Выключено", "Off", "Вимкнено", "Өшірулі", "O‘chiq", "Kapalı", "خاموش", "已关闭", "Apagado", "Aus")
T("home.state.on", "Защищено", "Protected", "Захищено", "Қорғалған", "Himoyalangan", "Korunuyor", "محافظت‌شده", "已保护", "Protegido", "Geschützt")
T("home.state.connecting", "Подключаю", "Connecting", "Підключаю", "Қосылуда", "Ulanmoqda", "Bağlanıyor", "در حال اتصال", "正在连接", "Conectando", "Verbinde")
T("home.state.disconnecting", "Отключаю", "Disconnecting", "Відключаю", "Ажыратылуда", "Uzilmoqda", "Kesiliyor", "در حال قطع", "正在断开", "Desconectando", "Trenne")
T("home.state.error", "Не вышло", "Didn't work", "Не вийшло", "Болмады", "Bo‘lmadi", "Olmadı", "نشد", "没有成功", "No funcionó", "Hat nicht geklappt")
T("home.caption.tap", "Нажмите на диск, чтобы включить VPN", "Tap the disc to turn the VPN on",
  "Натисніть на диск, щоб увімкнути VPN", "VPN қосу үшін дискіні басыңыз", "VPN’ni yoqish uchun diskni bosing",
  "VPN’i açmak için diske dokunun", "برای روشن کردن VPN روی دیسک بزنید", "点按圆盘即可开启 VPN",
  "Toca el disco para activar la VPN", "Tippe auf die Scheibe, um das VPN einzuschalten")
T("home.caption.addFirst", "Сначала добавьте подписку", "Add a subscription first", "Спочатку додайте підписку",
  "Алдымен жазылым қосыңыз", "Avval obuna qo‘shing", "Önce bir abonelik ekleyin", "اول یک اشتراک اضافه کنید",
  "请先添加订阅", "Primero añade una suscripción", "Füge zuerst ein Abo hinzu")
T("home.caption.cancel", "Ещё одно нажатие отменит", "Tap again to cancel", "Ще одне натискання скасує",
  "Тағы бір басу тоқтатады", "Yana bir bosish bekor qiladi", "İptal için tekrar dokunun", "برای لغو دوباره بزنید",
  "再点一次即可取消", "Toca otra vez para cancelar", "Nochmal tippen bricht ab")
T("home.caption.closing", "Закрываю туннель…", "Closing the tunnel…", "Закриваю тунель…", "Туннельді жабудамын…",
  "Tunnelni yopmoqdaman…", "Tünel kapatılıyor…", "در حال بستن تونل…", "正在关闭隧道…", "Cerrando el túnel…", "Schließe den Tunnel…")
T("home.caption.retry", "Нажмите на диск, чтобы попробовать снова", "Tap the disc to try again",
  "Натисніть на диск, щоб спробувати знову", "Қайта көру үшін дискіні басыңыз", "Qayta urinish uchun diskni bosing",
  "Tekrar denemek için diske dokunun", "برای تلاش دوباره روی دیسک بزنید", "点按圆盘重试", "Toca el disco para intentarlo de nuevo",
  "Tippe auf die Scheibe, um es noch einmal zu versuchen")
T("home.add.a11y", "Добавить подписку или сервер", "Add a subscription or a server", "Додати підписку або сервер",
  "Жазылым не сервер қосу", "Obuna yoki server qo‘shish", "Abonelik veya sunucu ekle", "افزودن اشتراک یا سرور",
  "添加订阅或服务器", "Añadir una suscripción o un servidor", "Abo oder Server hinzufügen")
T("home.regime.none", "нет сети", "no network", "немає мережі", "желі жоқ", "tarmoq yo‘q", "ağ yok", "شبکه نیست", "无网络", "sin red", "kein Netz")
T("home.first.text",
  "Вставьте ссылку от VPN-сервиса. Серверы разложатся по группам Home и LTE, остальные настройки подтянутся сами.",
  "Paste the link from your VPN service. The servers sort themselves into Home and LTE, and the rest is set up by itself.",
  "Вставте посилання від VPN-сервісу. Сервери самі розкладуться на групи Home і LTE, решта налаштується сама.",
  "VPN-сервистің сілтемесін қойыңыз. Серверлер Home және LTE топтарына өздері бөлінеді, қалғаны өзі бапталады.",
  "VPN xizmati bergan havolani qo‘ying. Serverlar Home va LTE guruhlariga o‘zi ajraladi, qolgani o‘zi sozlanadi.",
  "VPN hizmetinin bağlantısını yapıştırın. Sunucular kendiliğinden Home ve LTE gruplarına ayrılır, gerisi kendi ayarlanır.",
  "لینک سرویس VPN را بچسبانید. سرورها خودشان در گروه‌های Home و LTE مرتب می‌شوند و بقیه خودکار تنظیم می‌شود.",
  "粘贴 VPN 服务给你的链接。服务器会自动分到 Home 和 LTE 两组，其余设置也会自动完成。",
  "Pega el enlace de tu servicio VPN. Los servidores se ordenan solos en Home y LTE, y lo demás se configura solo.",
  "Füge den Link deines VPN-Dienstes ein. Die Server sortieren sich selbst in Home und LTE, der Rest stellt sich von allein ein.")
T("home.first.button", "Добавить подписку", "Add a subscription", "Додати підписку", "Жазылым қосу", "Obuna qo‘shish",
  "Abonelik ekle", "افزودن اشتراک", "添加订阅", "Añadir suscripción", "Abo hinzufügen")
T("disc.a11y.on", "Подключить VPN", "Turn the VPN on", "Увімкнути VPN", "VPN қосу", "VPN’ni yoqish", "VPN’i aç",
  "روشن کردن VPN", "开启 VPN", "Activar la VPN", "VPN einschalten")
T("disc.a11y.off", "Отключить VPN", "Turn the VPN off", "Вимкнути VPN", "VPN өшіру", "VPN’ni o‘chirish", "VPN’i kapat",
  "خاموش کردن VPN", "关闭 VPN", "Desactivar la VPN", "VPN ausschalten")
T("disc.a11y.cancel", "Подключение, нажмите, чтобы отменить", "Connecting, tap to cancel", "Підключення, натисніть, щоб скасувати",
  "Қосылуда, тоқтату үшін басыңыз", "Ulanmoqda, bekor qilish uchun bosing", "Bağlanıyor, iptal için dokunun",
  "در حال اتصال، برای لغو بزنید", "正在连接，点按取消", "Conectando, toca para cancelar", "Verbinde, tippen zum Abbrechen")
T("capsule.fastest", "Самый быстрый", "The fastest", "Найшвидший", "Ең жылдам", "Eng tez", "En hızlısı", "سریع‌ترین",
  "最快的", "El más rápido", "Der schnellste")
T("capsule.noServer", "Сервер не выбран", "No server chosen", "Сервер не вибрано", "Сервер таңдалмаған",
  "Server tanlanmagan", "Sunucu seçilmedi", "سروری انتخاب نشده", "未选择服务器", "Ningún servidor elegido", "Kein Server gewählt")
T("capsule.openList", "Откройте список, чтобы выбрать", "Open the list to choose one", "Відкрийте список, щоб вибрати",
  "Таңдау үшін тізімді ашыңыз", "Tanlash uchun ro‘yxatni oching", "Seçmek için listeyi açın", "برای انتخاب، فهرست را باز کنید",
  "打开列表进行选择", "Abre la lista para elegir", "Öffne die Liste zum Auswählen")
T("capsule.willFind", "Найду при подключении", "I'll find it when connecting", "Знайду під час підключення",
  "Қосылғанда табамын", "Ulanishda topaman", "Bağlanırken bulacağım", "هنگام اتصال پیدا می‌کنم", "连接时自动寻找",
  "Lo buscaré al conectar", "Ich suche ihn beim Verbinden")
T("mode.autoHome", "Авто Home", "Auto Home", "Авто Home", "Авто Home", "Avto Home", "Otomatik Home", "Home خودکار",
  "自动 Home", "Auto Home", "Auto Home")
T("mode.manual", "Свой", "Own", "Свій", "Өзім", "O‘zim", "Kendi", "دستی", "自选", "Propio", "Eigener")
T("mode.empty", "пусто", "empty", "порожньо", "бос", "bo‘sh", "boş", "خالی", "空", "vacío", "leer")
T("mode.noServers", "нет серверов", "no servers", "немає серверів", "сервер жоқ", "server yo‘q", "sunucu yok",
  "سروری نیست", "无服务器", "sin servidores", "keine Server")
T("mode.noLte",
  "В подписках нет LTE-серверов: Volta работает как Авто Home и не проверяет сайты",
  "Your subscriptions have no LTE servers: Volta works as Auto Home and doesn't check sites",
  "У підписках немає LTE-серверів: Volta працює як Авто Home і не перевіряє сайти",
  "Жазылымдарда LTE-сервер жоқ: Volta Авто Home ретінде жұмыс істейді және сайттарды тексермейді",
  "Obunalarda LTE-server yo‘q: Volta Avto Home sifatida ishlaydi va saytlarni tekshirmaydi",
  "Aboneliklerde LTE sunucusu yok: Volta Otomatik Home olarak çalışır ve siteleri denetlemez",
  "اشتراک‌ها سرور LTE ندارند: Volta به‌صورت Home خودکار کار می‌کند و سایت‌ها را بررسی نمی‌کند",
  "订阅里没有 LTE 服务器：Volta 按自动 Home 工作，不检测网站",
  "Tus suscripciones no tienen servidores LTE: Volta funciona como Auto Home y no comprueba sitios",
  "Deine Abos haben keine LTE-Server: Volta arbeitet als Auto Home und prüft keine Seiten")
T("manual.title", "Сервер выбран вручную", "Server chosen by hand", "Сервер вибрано вручну", "Сервер қолмен таңдалған",
  "Server qo‘lda tanlangan", "Sunucu elle seçildi", "سرور دستی انتخاب شده", "已手动选择服务器", "Servidor elegido a mano",
  "Server von Hand gewählt")
T("manual.backToAuto", "Вернуть авто", "Back to auto", "Повернути авто", "Автоға қайту", "Avtoga qaytish",
  "Otomatiğe dön", "بازگشت به خودکار", "恢复自动", "Volver a auto", "Zurück zu Auto")
T("speed.title", "Скорость", "Speed", "Швидкість", "Жылдамдық", "Tezlik", "Hız", "سرعت", "速度", "Velocidad", "Tempo")
T("speed.total", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@",
  "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@")
T("pick.title", "Почему этот сервер", "Why this server", "Чому цей сервер", "Неге осы сервер", "Nega aynan shu server",
  "Neden bu sunucu", "چرا این سرور", "为什么选这个服务器", "Por qué este servidor", "Warum dieser Server")
T("pick.counts", "Проверено %d, ответили %d", "Checked %d, answered %d", "Перевірено %d, відповіли %d",
  "Тексерілді %d, жауап берді %d", "Tekshirildi %d, javob berdi %d", "Denetlenen %d, yanıt veren %d",
  "بررسی‌شده %d، پاسخ‌داده %d", "已检测 %d 个，%d 个有响应", "Comprobados %d, respondieron %d", "Geprüft %d, geantwortet %d")
T("network.home", "Сеть Home", "Home network", "Мережа Home", "Home желісі", "Home tarmog‘i", "Home ağı", "شبکه Home",
  "Home 网络", "Red Home", "Home-Netz")
T("network.lte", "Сеть LTE", "LTE network", "Мережа LTE", "LTE желісі", "LTE tarmog‘i", "LTE ağı", "شبکه LTE",
  "LTE 网络", "Red LTE", "LTE-Netz")
T("network.none", "Нет интернета", "No internet", "Немає інтернету", "Интернет жоқ", "Internet yo‘q", "İnternet yok",
  "اینترنت نیست", "无网络连接", "Sin internet", "Kein Internet")
T("network.unknown", "Сеть не проверена", "Network not checked", "Мережу не перевірено", "Желі тексерілмеген",
  "Tarmoq tekshirilmagan", "Ağ denetlenmedi", "شبکه بررسی نشده", "尚未检测网络", "Red sin comprobar", "Netz nicht geprüft")
T("network.recheck", "Проверить сеть", "Check the network", "Перевірити мережу", "Желіні тексеру", "Tarmoqni tekshirish",
  "Ağı denetle", "بررسی شبکه", "检测网络", "Comprobar la red", "Netz prüfen")
T("network.whileConnected", "Проверяется перед подключением", "Checked before connecting", "Перевіряється перед підключенням",
  "Қосылар алдында тексеріледі", "Ulanishdan oldin tekshiriladi", "Bağlanmadan önce denetlenir", "پیش از اتصال بررسی می‌شود",
  "连接前检测", "Se comprueba antes de conectar", "Wird vor dem Verbinden geprüft")

# ------------------------------------------------------------------ connecting
T("phase.probing", "Проверяю сеть…", "Checking the network…", "Перевіряю мережу…", "Желіні тексерудемін…",
  "Tarmoqni tekshiryapman…", "Ağ denetleniyor…", "در حال بررسی شبکه…", "正在检测网络…", "Comprobando la red…", "Prüfe das Netz…")
T("phase.measuring", "Ищу быстрый сервер…", "Looking for a fast server…", "Шукаю швидкий сервер…", "Жылдам сервер іздеудемін…",
  "Tez server qidiryapman…", "Hızlı sunucu aranıyor…", "در حال یافتن سرور سریع…", "正在寻找快速服务器…",
  "Buscando un servidor rápido…", "Suche einen schnellen Server…")
T("phase.starting", "Поднимаю туннель…", "Starting the tunnel…", "Піднімаю тунель…", "Туннельді іске қосудамын…",
  "Tunnelni ishga tushiryapman…", "Tünel başlatılıyor…", "در حال راه‌اندازی تونل…", "正在启动隧道…", "Iniciando el túnel…",
  "Starte den Tunnel…")
T("connect.error.noServer", "Выберите сервер в списке", "Choose a server in the list", "Виберіть сервер у списку",
  "Тізімнен сервер таңдаңыз", "Ro‘yxatdan server tanlang", "Listeden bir sunucu seçin", "یک سرور از فهرست انتخاب کنید",
  "请在列表中选择服务器", "Elige un servidor de la lista", "Wähle einen Server in der Liste")
T("connect.error.noServers", "Сначала добавьте подписку", "Add a subscription first", "Спочатку додайте підписку",
  "Алдымен жазылым қосыңыз", "Avval obuna qo‘shing", "Önce bir abonelik ekleyin", "اول یک اشتراک اضافه کنید",
  "请先添加订阅", "Primero añade una suscripción", "Füge zuerst ein Abo hinzu")
T("connect.error.noneDialable", "Эта версия Volta не умеет открывать ни один из этих серверов",
  "This version of Volta can't open any of these servers", "Ця версія Volta не вміє відкривати жоден із цих серверів",
  "Volta-ның бұл нұсқасы бұл серверлердің ешқайсысын аша алмайды", "Volta’ning bu versiyasi bu serverlarning birortasini ocha olmaydi",
  "Volta’nın bu sürümü bu sunucuların hiçbirini açamıyor", "این نسخه Volta هیچ‌کدام از این سرورها را باز نمی‌کند",
  "此版本的 Volta 无法打开这些服务器中的任何一个", "Esta versión de Volta no puede abrir ninguno de estos servidores",
  "Diese Volta-Version kann keinen dieser Server öffnen")
T("connect.error.offline", "Нет интернета. Попробуйте позже.", "No internet. Try again later.", "Немає інтернету. Спробуйте пізніше.",
  "Интернет жоқ. Кейінірек көріңіз.", "Internet yo‘q. Keyinroq urinib ko‘ring.", "İnternet yok. Daha sonra deneyin.",
  "اینترنت نیست. بعداً دوباره امتحان کنید.", "没有网络。请稍后再试。", "Sin internet. Inténtalo más tarde.",
  "Kein Internet. Versuche es später.")
T("connect.error.emptyGroup", "В этой группе нет серверов", "This group has no servers", "У цій групі немає серверів",
  "Бұл топта сервер жоқ", "Bu guruhda server yo‘q", "Bu grupta sunucu yok", "این گروه سروری ندارد", "此分组没有服务器",
  "Este grupo no tiene servidores", "Diese Gruppe hat keine Server")
T("connect.error.noAnswer", "Серверы не ответили", "The servers didn't answer", "Сервери не відповіли", "Серверлер жауап бермеді",
  "Serverlar javob bermadi", "Sunucular yanıt vermedi", "سرورها پاسخ ندادند", "服务器没有响应", "Los servidores no respondieron",
  "Die Server haben nicht geantwortet")
T("balancer.noAnswer", "Серверы группы не ответили. Попробуйте позже.", "No server in the group answered. Try again later.",
  "Сервери групи не відповіли. Спробуйте пізніше.", "Топ серверлері жауап бермеді. Кейінірек көріңіз.",
  "Guruh serverlari javob bermadi. Keyinroq urinib ko‘ring.", "Gruptaki sunucular yanıt vermedi. Daha sonra deneyin.",
  "سرورهای گروه پاسخ ندادند. بعداً امتحان کنید.", "该分组的服务器没有响应。请稍后再试。",
  "Ningún servidor del grupo respondió. Inténtalo más tarde.", "Kein Server der Gruppe hat geantwortet. Versuche es später.")
T("balancer.picked", "Выбран «%@» · %d мс", "Picked “%@” · %d ms", "Вибрано «%@» · %d мс", "Таңдалды «%@» · %d мс",
  "Tanlandi: “%@” · %d ms", "Seçildi: “%@” · %d ms", "انتخاب شد: «%@» · %d میلی‌ثانیه", "已选择“%@”· %d 毫秒",
  "Elegido «%@» · %d ms", "Gewählt: „%@“ · %d ms")
T("ping.whileConnected", "Пока VPN включён, отклик может быть чуть выше настоящего",
  "While the VPN is on, the response time may read a little high",
  "Поки VPN увімкнено, відгук може бути трохи вищим за справжній",
  "VPN қосулы кезде жауап уақыты сәл жоғары көрінуі мүмкін",
  "VPN yoniqligida javob vaqti haqiqiydan biroz yuqori ko‘rinishi mumkin",
  "VPN açıkken yanıt süresi gerçeğinden biraz yüksek görünebilir",
  "تا وقتی VPN روشن است، زمان پاسخ ممکن است کمی بیشتر نشان داده شود",
  "VPN 开启时，测得的响应时间可能略高",
  "Con la VPN activada, el tiempo de respuesta puede salir algo alto",
  "Solange das VPN an ist, kann die Antwortzeit etwas zu hoch ausfallen")

# ------------------------------------------------------------------ servers screen
T("servers.bestInGroup", "Лучший сервер в группе", "Best server in a group", "Найкращий сервер у групі", "Топтағы ең жақсы сервер",
  "Guruhdagi eng yaxshi server", "Gruptaki en iyi sunucu", "بهترین سرور گروه", "分组中的最佳服务器", "Mejor servidor del grupo",
  "Bester Server der Gruppe")
T("servers.search", "Название, страна или адрес", "Name, country or address", "Назва, країна або адреса", "Атауы, елі не мекенжайы",
  "Nomi, davlati yoki manzili", "Ad, ülke veya adres", "نام، کشور یا نشانی", "名称、国家或地址", "Nombre, país o dirección",
  "Name, Land oder Adresse")
T("servers.pingAll", "Проверить отклик всех", "Check all response times", "Перевірити відгук усіх", "Барлығының жауабын тексеру",
  "Hammasining javobini tekshirish", "Tümünün yanıtını denetle", "بررسی پاسخ همه", "检测全部响应时间", "Comprobar la respuesta de todos",
  "Antwortzeit aller prüfen")
T("servers.sort", "Сортировка", "Sort", "Сортування", "Сұрыптау", "Saralash", "Sırala", "مرتب‌سازی", "排序", "Ordenar", "Sortieren")
T("sort.best", "Сначала быстрые", "Fastest first", "Спершу швидкі", "Алдымен жылдамдары", "Avval tezlari", "Önce hızlılar",
  "اول سریع‌ترها", "最快的在前", "Primero los rápidos", "Schnellste zuerst")
T("sort.name", "По названию", "By name", "За назвою", "Атауы бойынша", "Nomi bo‘yicha", "Ada göre", "بر اساس نام", "按名称",
  "Por nombre", "Nach Name")
T("sort.subscription", "По подписке", "By subscription", "За підпискою", "Жазылым бойынша", "Obuna bo‘yicha", "Aboneliğe göre",
  "بر اساس اشتراک", "按订阅", "Por suscripción", "Nach Abo")
T("pool.all", "Все", "All", "Усі", "Барлығы", "Hammasi", "Tümü", "همه", "全部", "Todos", "Alle")
T("pool.balancers", "Балансеры", "Balancers", "Балансери", "Балансерлер", "Balanserlar", "Dengeleyiciler", "متعادل‌کننده‌ها",
  "负载均衡", "Balanceadores", "Balancer")
T("group.balancer", "Балансер", "Balancer", "Балансер", "Балансер", "Balanser", "Dengeleyici", "متعادل‌کننده", "负载均衡",
  "Balanceador", "Balancer")
T("servers.favorites", "Избранные", "Favourites", "Обрані", "Таңдаулылар", "Sevimlilar", "Favoriler", "برگزیده‌ها", "收藏",
  "Favoritos", "Favoriten")
T("servers.allSubscriptions", "Все подписки", "All subscriptions", "Усі підписки", "Барлық жазылымдар", "Barcha obunalar",
  "Tüm abonelikler", "همه اشتراک‌ها", "全部订阅", "Todas las suscripciones", "Alle Abos")
T("servers.hideBalancers", "Скрывать балансеры", "Hide balancers", "Приховувати балансери", "Балансерлерді жасыру",
  "Balanserlarni yashirish", "Dengeleyicileri gizle", "پنهان کردن متعادل‌کننده‌ها", "隐藏负载均衡", "Ocultar balanceadores",
  "Balancer ausblenden")
T("servers.hideBalancers.hint", "Серверы «Авто» и «Балансер» не показываются в общем списке",
  "“Auto” and “Balancer” servers stay out of the common list", "Сервери «Авто» і «Балансер» не показуються в загальному списку",
  "«Авто» және «Балансер» серверлері жалпы тізімде көрсетілмейді", "“Avto” va “Balanser” serverlari umumiy ro‘yxatda ko‘rinmaydi",
  "“Otomatik” ve “Dengeleyici” sunucuları ortak listede gösterilmez", "سرورهای «خودکار» و «متعادل‌کننده» در فهرست کلی نمایش داده نمی‌شوند",
  "“自动”和“负载均衡”服务器不显示在总列表中", "Los servidores «Auto» y «Balanceador» no aparecen en la lista común",
  "„Auto“- und „Balancer“-Server erscheinen nicht in der gemeinsamen Liste")
T("servers.countWithCountries", "%@, стран: %d", "%@, countries: %d", "%@, країн: %d", "%@, елдер: %d", "%@, davlatlar: %d",
  "%@, ülke: %d", "%@، کشورها: %d", "%@，国家：%d", "%@, países: %d", "%@, Länder: %d")
T("servers.byCountry", "Страны", "Countries", "Країни", "Елдер", "Davlatlar", "Ülkeler", "کشورها", "国家", "Países", "Länder")
T("servers.flat", "Список", "List", "Список", "Тізім", "Ro‘yxat", "Liste", "فهرست", "列表", "Lista", "Liste")
T("servers.empty.title", "Серверов пока нет", "No servers yet", "Серверів поки немає", "Әзірге сервер жоқ", "Hozircha server yo‘q",
  "Henüz sunucu yok", "هنوز سروری نیست", "还没有服务器", "Aún no hay servidores", "Noch keine Server")
T("servers.empty.text", "Добавьте подписку или вставьте ссылку на сервер.", "Add a subscription or paste a server link.",
  "Додайте підписку або вставте посилання на сервер.", "Жазылым қосыңыз немесе сервер сілтемесін қойыңыз.",
  "Obuna qo‘shing yoki server havolasini qo‘ying.", "Bir abonelik ekleyin veya sunucu bağlantısı yapıştırın.",
  "یک اشتراک اضافه کنید یا لینک سرور را بچسبانید.", "添加订阅，或粘贴服务器链接。", "Añade una suscripción o pega el enlace de un servidor.",
  "Füge ein Abo hinzu oder einen Server-Link ein.")
T("servers.nothing.title", "Ничего не нашлось", "Nothing found", "Нічого не знайшлося", "Ештеңе табылмады", "Hech narsa topilmadi",
  "Hiçbir şey bulunamadı", "چیزی پیدا نشد", "没有找到", "No se encontró nada", "Nichts gefunden")
T("servers.nothing.text", "Измените фильтр или поисковый запрос.", "Change the filter or the search.", "Змініть фільтр або запит.",
  "Сүзгіні не іздеуді өзгертіңіз.", "Filtr yoki qidiruvni o‘zgartiring.", "Filtreyi veya aramayı değiştirin.",
  "فیلتر یا جست‌وجو را تغییر دهید.", "请更改筛选条件或搜索内容。", "Cambia el filtro o la búsqueda.", "Ändere den Filter oder die Suche.")
T("servers.now", "Сейчас", "Now", "Зараз", "Қазір", "Hozir", "Şu an", "اکنون", "当前", "Ahora", "Jetzt")
T("servers.from", "от %@", "from %@", "від %@", "%@ бастап", "%@ dan", "%@ itibaren", "از %@", "低至 %@", "desde %@", "ab %@")
T("server.manual", "Добавлен вручную", "Added by hand", "Додано вручну", "Қолмен қосылған", "Qo‘lda qo‘shilgan", "Elle eklendi",
  "دستی اضافه شده", "手动添加", "Añadido a mano", "Von Hand hinzugefügt")
T("server.unsupported", "Эта версия Volta его не открывает", "This version of Volta can't open it", "Ця версія Volta його не відкриває",
  "Volta-ның бұл нұсқасы оны ашпайды", "Volta’ning bu versiyasi uni ochmaydi", "Volta’nın bu sürümü bunu açamaz",
  "این نسخه Volta آن را باز نمی‌کند", "此版本的 Volta 无法打开它", "Esta versión de Volta no lo abre", "Diese Volta-Version öffnet ihn nicht")
T("server.recentlyDown", "Недавно не отвечал", "Didn't answer recently", "Нещодавно не відповідав", "Жақында жауап бермеді",
  "Yaqinda javob bermadi", "Yakın zamanda yanıt vermedi", "اخیراً پاسخ نداد", "最近没有响应", "No respondió hace poco",
  "Hat kürzlich nicht geantwortet")
T("pads.pick", "подобрать", "pick", "підібрати", "таңдау", "tanlash", "seç", "انتخاب", "挑选", "elegir", "wählen")
T("pads.pickBest", "подобрать лучший", "pick the best", "підібрати найкращий", "ең жақсысын таңдау", "eng yaxshisini tanlash",
  "en iyisini seç", "انتخاب بهترین", "挑选最佳", "elegir el mejor", "besten wählen")
T("pads.searching", "ищу", "searching", "шукаю", "іздеудемін", "qidiryapman", "aranıyor", "در حال جست‌وجو", "正在寻找",
  "buscando", "suche")
T("menu.ping", "Проверить отклик", "Check response time", "Перевірити відгук", "Жауапты тексеру", "Javobni tekshirish",
  "Yanıtı denetle", "بررسی پاسخ", "检测响应时间", "Comprobar respuesta", "Antwortzeit prüfen")
T("menu.favorite", "В избранное", "Add to favourites", "До обраного", "Таңдаулыларға", "Sevimlilarga", "Favorilere ekle",
  "افزودن به برگزیده‌ها", "加入收藏", "Añadir a favoritos", "Zu Favoriten")
T("menu.unfavorite", "Убрать из избранного", "Remove from favourites", "Прибрати з обраного", "Таңдаулылардан алып тастау",
  "Sevimlilardan olib tashlash", "Favorilerden çıkar", "حذف از برگزیده‌ها", "取消收藏", "Quitar de favoritos", "Aus Favoriten entfernen")
T("menu.group", "Группа", "Group", "Група", "Топ", "Guruh", "Grup", "گروه", "分组", "Grupo", "Gruppe")
T("menu.copy", "Скопировать ссылку", "Copy the link", "Скопіювати посилання", "Сілтемені көшіру", "Havolani nusxalash",
  "Bağlantıyı kopyala", "کپی لینک", "复制链接", "Copiar el enlace", "Link kopieren")
T("menu.copied", "Ссылка скопирована", "Link copied", "Посилання скопійовано", "Сілтеме көшірілді", "Havola nusxalandi",
  "Bağlantı kopyalandı", "لینک کپی شد", "链接已复制", "Enlace copiado", "Link kopiert")
T("menu.delete", "Удалить", "Delete", "Видалити", "Жою", "O‘chirish", "Sil", "حذف", "删除", "Eliminar", "Löschen")

# ------------------------------------------------------------------ subscriptions screen
T("subs.add", "Добавить подписку", "Add a subscription", "Додати підписку", "Жазылым қосу", "Obuna qo‘shish", "Abonelik ekle",
  "افزودن اشتراک", "添加订阅", "Añadir suscripción", "Abo hinzufügen")
T("subs.refreshAll", "Обновить все подписки", "Update all subscriptions", "Оновити всі підписки", "Барлық жазылымдарды жаңарту",
  "Barcha obunalarni yangilash", "Tüm abonelikleri güncelle", "به‌روزرسانی همه اشتراک‌ها", "更新全部订阅", "Actualizar todas",
  "Alle Abos aktualisieren")
T("subs.refresh", "Обновить подписку", "Update the subscription", "Оновити підписку", "Жазылымды жаңарту", "Obunani yangilash",
  "Aboneliği güncelle", "به‌روزرسانی اشتراک", "更新订阅", "Actualizar la suscripción", "Abo aktualisieren")
T("subs.empty.title", "Подписок пока нет", "No subscriptions yet", "Підписок поки немає", "Әзірге жазылым жоқ", "Hozircha obuna yo‘q",
  "Henüz abonelik yok", "هنوز اشتراکی نیست", "还没有订阅", "Aún no hay suscripciones", "Noch keine Abos")
T("subs.empty.text",
  "Добавьте одну или несколько: Volta объединит их серверы в общий список и сама разложит по группам Home и LTE.",
  "Add one or more: Volta merges their servers into one list and sorts them into Home and LTE by itself.",
  "Додайте одну або кілька: Volta об’єднає їхні сервери в один список і сама розкладе на групи Home і LTE.",
  "Бір немесе бірнешеуін қосыңыз: Volta олардың серверлерін бір тізімге біріктіріп, Home және LTE топтарына өзі бөледі.",
  "Bitta yoki bir nechtasini qo‘shing: Volta ularning serverlarini bitta ro‘yxatga birlashtiradi va Home hamda LTE guruhlariga o‘zi ajratadi.",
  "Bir veya birkaç tane ekleyin: Volta sunucularını tek listede toplar ve Home ile LTE gruplarına kendisi ayırır.",
  "یک یا چند اشتراک اضافه کنید: Volta سرورهایشان را در یک فهرست جمع می‌کند و خودش در گروه‌های Home و LTE مرتب می‌کند.",
  "可以添加一个或多个：Volta 会把它们的服务器合并成一个列表，并自动分到 Home 和 LTE。",
  "Añade una o varias: Volta junta sus servidores en una lista y los ordena solo en Home y LTE.",
  "Füge eines oder mehrere hinzu: Volta fasst ihre Server in einer Liste zusammen und sortiert sie selbst in Home und LTE.")
T("subs.hint", "Нажмите на подписку, чтобы переименовать или удалить её.", "Tap a subscription to rename or delete it.",
  "Натисніть на підписку, щоб перейменувати або видалити її.", "Атын өзгерту не жою үшін жазылымды басыңыз.",
  "Nomini o‘zgartirish yoki o‘chirish uchun obunani bosing.", "Yeniden adlandırmak veya silmek için aboneliğe dokunun.",
  "برای تغییر نام یا حذف، روی اشتراک بزنید.", "点按订阅可重命名或删除。", "Toca una suscripción para renombrarla o eliminarla.",
  "Tippe auf ein Abo, um es umzubenennen oder zu löschen.")
T("subs.traffic", "%@ из %@", "%@ of %@", "%@ з %@", "%@ / %@", "%@ / %@", "%@ / %@", "%@ از %@", "%@ / %@", "%@ de %@", "%@ von %@")
T("subs.unlimited", "Без лимита трафика", "No traffic limit", "Без ліміту трафіку", "Трафик шектеусіз", "Trafik cheklovsiz",
  "Trafik sınırı yok", "بدون محدودیت ترافیک", "不限流量", "Sin límite de tráfico", "Kein Datenlimit")
T("subs.hidden", "ссылка скрыта", "link hidden", "посилання приховано", "сілтеме жасырылған", "havola yashirilgan",
  "bağlantı gizli", "لینک پنهان است", "链接已隐藏", "enlace oculto", "Link verborgen")
P("expiry.daysLeft",
  ("остался %d день", "осталось %d дня", "осталось %d дней"),
  ("%d day left", "%d days left"),
  ("залишився %d день", "залишилося %d дні", "залишилося %d днів"),
  ("%d күн қалды", "%d күн қалды"),
  ("%d kun qoldi", "%d kun qoldi"),
  ("%d gün kaldı", "%d gün kaldı"),
  ("%d روز مانده", "%d روز مانده"),
  ("剩余 %d 天", "剩余 %d 天"),
  ("queda %d día", "quedan %d días"),
  ("noch %d Tag", "noch %d Tage"))
T("expiry.today", "истекает сегодня", "expires today", "закінчується сьогодні", "бүгін бітеді", "bugun tugaydi", "bugün bitiyor",
  "امروز تمام می‌شود", "今天到期", "vence hoy", "läuft heute ab")
T("expiry.expired", "истекла", "expired", "закінчилася", "мерзімі өтті", "muddati tugagan", "süresi doldu", "منقضی شده", "已过期",
  "vencida", "abgelaufen")
T("sub.neverUpdated", "ещё не обновлялась", "not updated yet", "ще не оновлювалася", "әлі жаңартылмаған", "hali yangilanmagan",
  "henüz güncellenmedi", "هنوز به‌روز نشده", "尚未更新", "aún sin actualizar", "noch nicht aktualisiert")
T("sub.updated", "обновлена %@", "updated %@", "оновлено %@", "жаңартылды: %@", "yangilandi: %@", "güncellendi: %@",
  "به‌روز شد %@", "更新于%@", "actualizada %@", "aktualisiert %@")
T("editor.name", "Название", "Name", "Назва", "Атауы", "Nomi", "Ad", "نام", "名称", "Nombre", "Name")
T("editor.link", "Ссылка на подписку", "Subscription link", "Посилання на підписку", "Жазылым сілтемесі", "Obuna havolasi",
  "Abonelik bağlantısı", "لینک اشتراک", "订阅链接", "Enlace de la suscripción", "Abo-Link")
T("editor.hiddenLink",
  "Сервис скрыл ссылку: её нельзя посмотреть или изменить, но подписка обновляется как обычно.",
  "The service hid the link: it can't be viewed or changed, but the subscription updates as usual.",
  "Сервіс приховав посилання: його не можна переглянути чи змінити, але підписка оновлюється як завжди.",
  "Сервис сілтемені жасырған: оны көру не өзгерту мүмкін емес, бірақ жазылым әдеттегідей жаңарады.",
  "Xizmat havolani yashirgan: uni ko‘rish yoki o‘zgartirib bo‘lmaydi, lekin obuna odatdagidek yangilanadi.",
  "Hizmet bağlantıyı gizledi: görülemez veya değiştirilemez, ama abonelik her zamanki gibi güncellenir.",
  "سرویس لینک را پنهان کرده است: نمی‌توان آن را دید یا تغییر داد، اما اشتراک مثل همیشه به‌روز می‌شود.",
  "服务商隐藏了链接：无法查看或修改，但订阅会照常更新。",
  "El servicio ocultó el enlace: no se puede ver ni cambiar, pero la suscripción se actualiza como siempre.",
  "Der Dienst hat den Link verborgen: Er lässt sich nicht ansehen oder ändern, das Abo aktualisiert sich aber wie gewohnt.")
T("editor.save", "Сохранить", "Save", "Зберегти", "Сақтау", "Saqlash", "Kaydet", "ذخیره", "保存", "Guardar", "Speichern")
T("editor.refresh", "Обновить сейчас", "Update now", "Оновити зараз", "Қазір жаңарту", "Hozir yangilash", "Şimdi güncelle",
  "اکنون به‌روز کن", "立即更新", "Actualizar ahora", "Jetzt aktualisieren")
T("editor.refresh.hint", "Скачать свежий список серверов", "Download a fresh server list", "Завантажити свіжий список серверів",
  "Жаңа серверлер тізімін жүктеу", "Yangi serverlar ro‘yxatini yuklash", "Güncel sunucu listesini indir", "دریافت فهرست تازه سرورها",
  "下载最新的服务器列表", "Descargar una lista nueva de servidores", "Frische Serverliste laden")
T("editor.support", "Поддержка сервиса", "Service support", "Підтримка сервісу", "Сервистің қолдауы", "Xizmat yordami",
  "Hizmet desteği", "پشتیبانی سرویس", "服务商支持", "Soporte del servicio", "Support des Dienstes")
T("editor.copy", "Скопировать ссылку", "Copy the link", "Скопіювати посилання", "Сілтемені көшіру", "Havolani nusxalash",
  "Bağlantıyı kopyala", "کپی لینک", "复制链接", "Copiar el enlace", "Link kopieren")
T("editor.copied", "Ссылка скопирована", "Link copied", "Посилання скопійовано", "Сілтеме көшірілді", "Havola nusxalandi",
  "Bağlantı kopyalandı", "لینک کپی شد", "链接已复制", "Enlace copiado", "Link kopiert")
T("editor.delete", "Удалить подписку", "Delete the subscription", "Видалити підписку", "Жазылымды жою", "Obunani o‘chirish",
  "Aboneliği sil", "حذف اشتراک", "删除订阅", "Eliminar la suscripción", "Abo löschen")
T("editor.delete.title", "Удалить подписку?", "Delete the subscription?", "Видалити підписку?", "Жазылымды жою керек пе?",
  "Obuna o‘chirilsinmi?", "Abonelik silinsin mi?", "اشتراک حذف شود؟", "删除此订阅？", "¿Eliminar la suscripción?", "Abo löschen?")
T("editor.delete.text", "Уйдут её серверы. Остальные подписки останутся как есть.",
  "Its servers go with it. Other subscriptions stay as they are.", "Підуть її сервери. Інші підписки залишаться як є.",
  "Оның серверлері де жойылады. Басқа жазылымдар өзгеріссіз қалады.", "Uning serverlari ham o‘chadi. Boshqa obunalar o‘zgarmaydi.",
  "Sunucuları da gider. Diğer abonelikler olduğu gibi kalır.", "سرورهایش هم حذف می‌شوند. اشتراک‌های دیگر دست نمی‌خورند.",
  "它的服务器也会一起删除，其他订阅保持不变。", "Sus servidores se van con ella. Las demás suscripciones quedan igual.",
  "Seine Server gehen mit. Andere Abos bleiben, wie sie sind.")
T("editor.delete.confirm", "Удалить", "Delete", "Видалити", "Жою", "O‘chirish", "Sil", "حذف", "删除", "Eliminar", "Löschen")

# ------------------------------------------------------------------ import screen
T("import.title", "Добавить", "Add", "Додати", "Қосу", "Qo‘shish", "Ekle", "افزودن", "添加", "Añadir", "Hinzufügen")
T("import.subtitle", "Подписку, сервер или ключ. Вставьте как есть, Volta разберётся сама.",
  "A subscription, a server or a key. Paste it as it is, Volta will figure it out.",
  "Підписку, сервер або ключ. Вставте як є, Volta розбереться сама.",
  "Жазылым, сервер не кілт. Бар күйінде қойыңыз, Volta өзі түсінеді.",
  "Obuna, server yoki kalit. Qanday bo‘lsa shunday qo‘ying, Volta o‘zi tushunadi.",
  "Abonelik, sunucu veya anahtar. Olduğu gibi yapıştırın, Volta kendisi anlar.",
  "اشتراک، سرور یا کلید. همان‌طور که هست بچسبانید، Volta خودش می‌فهمد.",
  "订阅、服务器或密钥。原样粘贴即可，Volta 会自己识别。",
  "Una suscripción, un servidor o una clave. Pégalo tal cual, Volta se encarga.",
  "Ein Abo, ein Server oder ein Schlüssel. Füge es einfach ein, Volta erkennt es selbst.")
T("import.placeholder",
  "Вставьте сюда ссылку на подписку или на сервер (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Paste a subscription link or a server link here (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Вставте сюди посилання на підписку або на сервер (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Мұнда жазылым не сервер сілтемесін қойыңыз (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Bu yerga obuna yoki server havolasini qo‘ying (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Abonelik veya sunucu bağlantısını buraya yapıştırın (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "لینک اشتراک یا سرور را اینجا بچسبانید (vless://، vmess://، trojan://، ss://، hysteria2://)",
  "在此粘贴订阅链接或服务器链接（vless://、vmess://、trojan://、ss://、hysteria2://）",
  "Pega aquí el enlace de la suscripción o del servidor (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Füge hier einen Abo- oder Server-Link ein (vless://, vmess://, trojan://, ss://, hysteria2://)")
T("import.paste", "Вставить из буфера", "Paste from clipboard", "Вставити з буфера", "Буферден қою", "Buferdan qo‘yish",
  "Panodan yapıştır", "چسباندن از کلیپ‌بورد", "从剪贴板粘贴", "Pegar del portapapeles", "Aus Zwischenablage einfügen")
T("import.clipboardEmpty", "В буфере обмена пусто", "The clipboard is empty", "Буфер обміну порожній", "Алмасу буфері бос",
  "Bufer bo‘sh", "Pano boş", "کلیپ‌بورد خالی است", "剪贴板是空的", "El portapapeles está vacío", "Die Zwischenablage ist leer")
T("import.ownName", "Название, если хотите своё", "Your own name, if you like", "Назва, якщо хочете свою", "Қаласаңыз, өз атауыңыз",
  "Xohlasangiz, o‘z nomingiz", "İsterseniz kendi adınız", "نام دلخواه، اگر می‌خواهید", "自定义名称（可选）",
  "Tu propio nombre, si quieres", "Eigener Name, falls gewünscht")
T("import.openFile", "Открыть файл настроек", "Open a settings file", "Відкрити файл налаштувань", "Баптау файлын ашу",
  "Sozlamalar faylini ochish", "Ayar dosyası aç", "باز کردن فایل تنظیمات", "打开配置文件", "Abrir un archivo de ajustes",
  "Einstellungsdatei öffnen")
T("import.openFile.hint", "Файл, который прислал VPN-сервис", "A file the VPN service sent", "Файл, який надіслав VPN-сервіс",
  "VPN-сервис жіберген файл", "VPN xizmati yuborgan fayl", "VPN hizmetinin gönderdiği dosya", "فایلی که سرویس VPN فرستاده",
  "VPN 服务发给你的文件", "Un archivo que envió el servicio VPN", "Eine Datei vom VPN-Dienst")
T("import.fileUnreadable", "Не получилось прочитать файл", "Couldn't read the file", "Не вдалося прочитати файл",
  "Файлды оқу мүмкін болмады", "Faylni o‘qib bo‘lmadi", "Dosya okunamadı", "خواندن فایل ممکن نشد", "无法读取该文件",
  "No se pudo leer el archivo", "Die Datei ließ sich nicht lesen")
T("import.what", "Что сюда можно вставить", "What you can paste here", "Що сюди можна вставити", "Мұнда не қоюға болады",
  "Bu yerga nima qo‘yish mumkin", "Buraya ne yapıştırılabilir", "چه چیزی می‌توان اینجا چسباند", "这里可以粘贴什么",
  "Qué puedes pegar aquí", "Was du hier einfügen kannst")
T("import.fmt.sub", "Подписка", "Subscription", "Підписка", "Жазылым", "Obuna", "Abonelik", "اشتراک", "订阅", "Suscripción", "Abo")
T("import.fmt.sub.text", "Ссылка от VPN-сервиса, обычная или зашифрованная. Серверы в ней обновляются сами",
  "The link from your VPN service, plain or encrypted. Its servers update by themselves",
  "Посилання від VPN-сервісу, звичайне або зашифроване. Сервери в ньому оновлюються самі",
  "VPN-сервистің сілтемесі, қарапайым не шифрланған. Ондағы серверлер өздері жаңарады",
  "VPN xizmati havolasi, oddiy yoki shifrlangan. Undagi serverlar o‘zi yangilanadi",
  "VPN hizmetinin bağlantısı, düz veya şifreli. İçindeki sunucular kendiliğinden güncellenir",
  "لینک سرویس VPN، ساده یا رمزنگاری‌شده. سرورهای آن خودکار به‌روز می‌شوند",
  "VPN 服务的链接，普通或加密的都可以。其中的服务器会自动更新",
  "El enlace de tu servicio VPN, normal o cifrado. Sus servidores se actualizan solos",
  "Der Link deines VPN-Dienstes, normal oder verschlüsselt. Seine Server aktualisieren sich selbst")
T("import.fmt.server", "Сервер", "Server", "Сервер", "Сервер", "Server", "Sunucu", "سرور", "服务器", "Servidor", "Server")
T("import.fmt.server.text", "Ссылка на один сервер: vless://, vmess://, trojan://, ss://, hysteria2://",
  "A link to one server: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Посилання на один сервер: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Бір серверге сілтеме: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Bitta serverga havola: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Tek bir sunucunun bağlantısı: vless://, vmess://, trojan://, ss://, hysteria2://",
  "لینک یک سرور: vless://، vmess://، trojan://، ss://، hysteria2://",
  "单个服务器的链接：vless://、vmess://、trojan://、ss://、hysteria2://",
  "El enlace de un servidor: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Ein Link zu einem Server: vless://, vmess://, trojan://, ss://, hysteria2://")
T("import.fmt.volta", "Ссылка Volta", "Volta link", "Посилання Volta", "Volta сілтемесі", "Volta havolasi", "Volta bağlantısı",
  "لینک Volta", "Volta 链接", "Enlace de Volta", "Volta-Link")
T("import.fmt.volta.text", "Ссылка volta://, которую даёт VPN-сервис: обычная или зашифрованная",
  "A volta:// link from the VPN service: plain or encrypted", "Посилання volta://, яке дає VPN-сервіс: звичайне або зашифроване",
  "VPN-сервис беретін volta:// сілтемесі: қарапайым не шифрланған", "VPN xizmati beradigan volta:// havolasi: oddiy yoki shifrlangan",
  "VPN hizmetinin verdiği volta:// bağlantısı: düz veya şifreli", "لینک volta:// که سرویس VPN می‌دهد: ساده یا رمزنگاری‌شده",
  "VPN 服务提供的 volta:// 链接：普通或加密", "Un enlace volta:// del servicio VPN: normal o cifrado",
  "Ein volta://-Link vom VPN-Dienst: normal oder verschlüsselt")
T("import.fmt.file", "Файл настроек", "Settings file", "Файл налаштувань", "Баптау файлы", "Sozlamalar fayli", "Ayar dosyası",
  "فایل تنظیمات", "配置文件", "Archivo de ajustes", "Einstellungsdatei")
T("import.fmt.file.text", "Файл, который прислал сервис (.conf или .json): его можно открыть кнопкой выше",
  "A file the service sent (.conf or .json): open it with the button above",
  "Файл, який надіслав сервіс (.conf або .json): його можна відкрити кнопкою вище",
  "Сервис жіберген файл (.conf не .json): оны жоғарыдағы батырмамен ашуға болады",
  "Xizmat yuborgan fayl (.conf yoki .json): uni yuqoridagi tugma bilan ochish mumkin",
  "Hizmetin gönderdiği dosya (.conf veya .json): yukarıdaki düğmeyle açabilirsiniz",
  "فایلی که سرویس فرستاده (‎.conf یا ‎.json): با دکمه بالا بازش کنید",
  "服务商发来的文件（.conf 或 .json）：用上方按钮打开",
  "Un archivo que envió el servicio (.conf o .json): ábrelo con el botón de arriba",
  "Eine Datei vom Dienst (.conf oder .json): öffne sie mit der Schaltfläche oben")
T("import.action.subscription", "Добавить подписку", "Add the subscription", "Додати підписку", "Жазылымды қосу",
  "Obunani qo‘shish", "Aboneliği ekle", "افزودن اشتراک", "添加订阅", "Añadir la suscripción", "Abo hinzufügen")
T("import.action.server", "Добавить сервер", "Add the server", "Додати сервер", "Серверді қосу", "Serverni qo‘shish",
  "Sunucuyu ekle", "افزودن سرور", "添加服务器", "Añadir el servidor", "Server hinzufügen")
T("import.action.servers", "Добавить серверы", "Add the servers", "Додати сервери", "Серверлерді қосу", "Serverlarni qo‘shish",
  "Sunucuları ekle", "افزودن سرورها", "添加服务器", "Añadir los servidores", "Server hinzufügen")
T("import.action.import", "Импортировать", "Import", "Імпортувати", "Импорттау", "Import qilish", "İçe aktar", "وارد کردن",
  "导入", "Importar", "Importieren")
T("import.action.run", "Выполнить", "Run", "Виконати", "Орындау", "Bajarish", "Çalıştır", "اجرا", "执行", "Ejecutar", "Ausführen")
T("detect.empty", "Пока пусто", "Nothing yet", "Поки порожньо", "Әзірге бос", "Hozircha bo‘sh", "Henüz boş", "هنوز خالی است",
  "还是空的", "Aún vacío", "Noch leer")
T("detect.empty.text", "Вставьте ссылку или ключ", "Paste a link or a key", "Вставте посилання або ключ", "Сілтеме не кілт қойыңыз",
  "Havola yoki kalit qo‘ying", "Bir bağlantı veya anahtar yapıştırın", "یک لینک یا کلید بچسبانید", "粘贴链接或密钥",
  "Pega un enlace o una clave", "Füge einen Link oder Schlüssel ein")
T("detect.sub", "Подписка", "Subscription", "Підписка", "Жазылым", "Obuna", "Abonelik", "اشتراک", "订阅", "Suscripción", "Abo")
T("detect.sub.text", "Серверы будут обновляться сами", "The servers update by themselves", "Сервери оновлюватимуться самі",
  "Серверлер өздері жаңарып отырады", "Serverlar o‘zi yangilanib turadi", "Sunucular kendiliğinden güncellenir",
  "سرورها خودکار به‌روز می‌شوند", "服务器会自动更新", "Los servidores se actualizan solos", "Die Server aktualisieren sich selbst")
T("detect.hiddenSub", "Подписка со скрытой ссылкой", "Subscription with a hidden link", "Підписка з прихованим посиланням",
  "Жасырын сілтемелі жазылым", "Havolasi yashirin obuna", "Gizli bağlantılı abonelik", "اشتراک با لینک پنهان", "隐藏链接的订阅",
  "Suscripción con enlace oculto", "Abo mit verborgenem Link")
T("detect.hiddenSub.text", "Адрес не будет виден в приложении", "The address won't be shown in the app",
  "Адресу не буде видно в застосунку", "Мекенжай қолданбада көрінбейді", "Manzil ilovada ko‘rinmaydi",
  "Adres uygulamada görünmez", "نشانی در برنامه نمایش داده نمی‌شود", "应用内不会显示地址", "La dirección no se verá en la app",
  "Die Adresse wird in der App nicht angezeigt")
T("detect.encrypted", "Зашифрованная подписка", "Encrypted subscription", "Зашифрована підписка", "Шифрланған жазылым",
  "Shifrlangan obuna", "Şifreli abonelik", "اشتراک رمزنگاری‌شده", "加密订阅", "Suscripción cifrada", "Verschlüsseltes Abo")
T("detect.encrypted.text", "Volta откроет её сама, адрес останется скрытым", "Volta opens it itself, the address stays hidden",
  "Volta відкриє її сама, адреса залишиться прихованою", "Volta оны өзі ашады, мекенжай жасырын қалады",
  "Volta uni o‘zi ochadi, manzil yashirin qoladi", "Volta onu kendisi açar, adres gizli kalır",
  "Volta خودش آن را باز می‌کند و نشانی پنهان می‌ماند", "Volta 会自行打开，地址保持隐藏", "Volta la abre sola, la dirección queda oculta",
  "Volta öffnet es selbst, die Adresse bleibt verborgen")
T("detect.server", "Сервер %@", "%@ server", "Сервер %@", "%@ сервері", "%@ server", "%@ sunucusu", "سرور %@", "%@ 服务器",
  "Servidor %@", "%@-Server")
T("detect.server.text", "Добавится в общий список серверов", "Joins the common server list", "Додасться до загального списку серверів",
  "Жалпы серверлер тізіміне қосылады", "Umumiy serverlar ro‘yxatiga qo‘shiladi", "Ortak sunucu listesine eklenir",
  "به فهرست کلی سرورها اضافه می‌شود", "将加入服务器总列表", "Se añade a la lista común de servidores", "Kommt in die gemeinsame Serverliste")
T("detect.servers.text", "Все добавятся в общий список", "All of them join the common list", "Усі додадуться до загального списку",
  "Барлығы жалпы тізімге қосылады", "Hammasi umumiy ro‘yxatga qo‘shiladi", "Hepsi ortak listeye eklenir",
  "همه به فهرست کلی اضافه می‌شوند", "全部加入总列表", "Todos se añaden a la lista común", "Alle kommen in die gemeinsame Liste")
T("detect.conf", "Файл настроек сервера", "Server settings file", "Файл налаштувань сервера", "Сервер баптау файлы",
  "Server sozlamalari fayli", "Sunucu ayar dosyası", "فایل تنظیمات سرور", "服务器配置文件", "Archivo de ajustes del servidor",
  "Server-Einstellungsdatei")
T("detect.conf.text", "Станет одним сервером в списке", "Becomes one server in the list", "Стане одним сервером у списку",
  "Тізімде бір сервер болады", "Ro‘yxatda bitta server bo‘ladi", "Listede tek bir sunucu olur", "یک سرور در فهرست می‌شود",
  "将成为列表中的一个服务器", "Será un servidor en la lista", "Wird ein Server in der Liste")
T("detect.json", "Настройки серверов", "Server settings", "Налаштування серверів", "Сервер баптаулары", "Server sozlamalari",
  "Sunucu ayarları", "تنظیمات سرورها", "服务器配置", "Ajustes de servidores", "Server-Einstellungen")
T("detect.json.text", "Серверы добавятся в общий список", "The servers join the common list", "Сервери додадуться до загального списку",
  "Серверлер жалпы тізімге қосылады", "Serverlar umumiy ro‘yxatga qo‘shiladi", "Sunucular ortak listeye eklenir",
  "سرورها به فهرست کلی اضافه می‌شوند", "服务器将加入总列表", "Los servidores se añaden a la lista común",
  "Die Server kommen in die gemeinsame Liste")
T("detect.control", "Ссылка управления", "Control link", "Посилання керування", "Басқару сілтемесі", "Boshqaruv havolasi",
  "Kontrol bağlantısı", "لینک کنترل", "控制链接", "Enlace de control", "Steuer-Link")
T("detect.control.text", "Не добавляется, а включает или выключает VPN", "Isn't added: it turns the VPN on or off",
  "Не додається, а вмикає або вимикає VPN", "Қосылмайды, VPN-ді қосады не өшіреді", "Qo‘shilmaydi, VPN’ni yoqadi yoki o‘chiradi",
  "Eklenmez, VPN’i açar veya kapatır", "اضافه نمی‌شود، VPN را روشن یا خاموش می‌کند", "不会被添加，而是开关 VPN",
  "No se añade: enciende o apaga la VPN", "Wird nicht hinzugefügt, sondern schaltet das VPN ein oder aus")
T("detect.amnezia", "Ключ AmneziaVPN", "AmneziaVPN key", "Ключ AmneziaVPN", "AmneziaVPN кілті", "AmneziaVPN kaliti",
  "AmneziaVPN anahtarı", "کلید AmneziaVPN", "AmneziaVPN 密钥", "Clave de AmneziaVPN", "AmneziaVPN-Schlüssel")
T("detect.amnezia.text", "Пока не работает в Volta для iPhone", "Doesn't work in Volta for iPhone yet",
  "Поки не працює у Volta для iPhone", "Әзірге iPhone-ға арналған Volta-да жұмыс істемейді", "Hozircha iPhone uchun Volta’da ishlamaydi",
  "iPhone için Volta’da henüz çalışmıyor", "فعلاً در Volta برای iPhone کار نمی‌کند", "iPhone 版 Volta 暂不支持",
  "Aún no funciona en Volta para iPhone", "Funktioniert in Volta für iPhone noch nicht")
T("detect.unknown", "Не похоже на ссылку", "Doesn't look like a link", "Не схоже на посилання", "Сілтемеге ұқсамайды",
  "Havolaga o‘xshamaydi", "Bağlantıya benzemiyor", "شبیه لینک نیست", "看起来不像链接", "No parece un enlace", "Sieht nicht wie ein Link aus")
T("detect.unknown.text", "Проверьте, что скопировали её целиком", "Check that you copied all of it",
  "Перевірте, що скопіювали його повністю", "Толық көшіргеніңізді тексеріңіз", "To‘liq nusxalaganingizni tekshiring",
  "Tamamını kopyaladığınızdan emin olun", "بررسی کنید که آن را کامل کپی کرده‌اید", "请确认已完整复制",
  "Comprueba que lo copiaste entero", "Prüfe, ob du ihn vollständig kopiert hast")

# ------------------------------------------------------------------ settings
T("settings.connection", "Подключение", "Connection", "Підключення", "Қосылу", "Ulanish", "Bağlantı", "اتصال", "连接",
  "Conexión", "Verbindung")
T("settings.keep", "Держать VPN включённым", "Keep the VPN on", "Тримати VPN увімкненим", "VPN-ді қосулы ұстау",
  "VPN’ni yoniq ushlash", "VPN’i açık tut", "VPN را روشن نگه دار", "保持 VPN 开启", "Mantener la VPN activada", "VPN eingeschaltet lassen")
T("settings.keep.hint",
  "iPhone сам включит VPN снова, если соединение пропадёт. Выключить VPN можно только в Volta.",
  "The iPhone turns the VPN back on if the connection drops. You can turn it off only in Volta.",
  "iPhone сам увімкне VPN знову, якщо з’єднання зникне. Вимкнути VPN можна лише у Volta.",
  "Байланыс үзілсе, iPhone VPN-ді өзі қайта қосады. VPN-ді тек Volta-да өшіруге болады.",
  "Aloqa uzilsa, iPhone VPN’ni o‘zi qayta yoqadi. VPN’ni faqat Volta’da o‘chirish mumkin.",
  "Bağlantı koparsa iPhone VPN’i kendisi yeniden açar. VPN yalnızca Volta’dan kapatılabilir.",
  "اگر اتصال قطع شود، iPhone خودش VPN را دوباره روشن می‌کند. خاموش کردن VPN فقط در Volta ممکن است.",
  "连接断开时 iPhone 会自动重新开启 VPN。只能在 Volta 中关闭 VPN。",
  "El iPhone vuelve a activar la VPN si se corta la conexión. Solo se puede apagar desde Volta.",
  "Das iPhone schaltet das VPN wieder ein, wenn die Verbindung abreißt. Ausschalten geht nur in Volta.")
T("settings.vpnProfile", "Настройки iPhone", "iPhone settings", "Налаштування iPhone", "iPhone баптаулары", "iPhone sozlamalari",
  "iPhone ayarları", "تنظیمات iPhone", "iPhone 设置", "Ajustes del iPhone", "iPhone-Einstellungen")
T("settings.vpnProfile.hint", "Страница Volta в Настройках: язык, уведомления и разрешения",
  "Volta's page in Settings: language, notifications and permissions", "Сторінка Volta в Налаштуваннях: мова, сповіщення і дозволи",
  "Баптаулардағы Volta беті: тіл, хабарландырулар мен рұқсаттар", "Sozlamalardagi Volta sahifasi: til, bildirishnomalar va ruxsatlar",
  "Ayarlar’daki Volta sayfası: dil, bildirimler ve izinler", "صفحه Volta در تنظیمات: زبان، اعلان‌ها و مجوزها",
  "“设置”中的 Volta 页面：语言、通知和权限", "La página de Volta en Ajustes: idioma, avisos y permisos",
  "Voltas Seite in den Einstellungen: Sprache, Mitteilungen und Berechtigungen")
T("settings.checks", "Подписки и проверки", "Subscriptions and checks", "Підписки та перевірки", "Жазылымдар мен тексерулер",
  "Obunalar va tekshiruvlar", "Abonelikler ve denetimler", "اشتراک‌ها و بررسی‌ها", "订阅和检测", "Suscripciones y comprobaciones",
  "Abos und Prüfungen")
T("settings.refreshOnLaunch", "Обновлять подписки при запуске", "Update subscriptions on launch", "Оновлювати підписки під час запуску",
  "Іске қосқанда жазылымдарды жаңарту", "Ishga tushganda obunalarni yangilash", "Açılışta abonelikleri güncelle",
  "به‌روزرسانی اشتراک‌ها هنگام اجرا", "启动时更新订阅", "Actualizar suscripciones al abrir", "Abos beim Start aktualisieren")
T("settings.refreshOnLaunch.hint", "Серверы обновляются при открытии приложения.", "The servers update when you open the app.",
  "Сервери оновлюються під час відкриття застосунку.", "Қолданбаны ашқанда серверлер жаңарады.", "Ilova ochilganda serverlar yangilanadi.",
  "Uygulama açıldığında sunucular güncellenir.", "با باز کردن برنامه، سرورها به‌روز می‌شوند.", "打开应用时更新服务器。",
  "Los servidores se actualizan al abrir la app.", "Die Server werden beim Öffnen der App aktualisiert.")
T("settings.probe", "Проверять сеть при запуске", "Check the network on launch", "Перевіряти мережу під час запуску",
  "Іске қосқанда желіні тексеру", "Ishga tushganda tarmoqni tekshirish", "Açılışta ağı denetle", "بررسی شبکه هنگام اجرا",
  "启动时检测网络", "Comprobar la red al abrir", "Netz beim Start prüfen")
T("settings.probe.hint", "Сразу видно, какая сейчас сеть: Home или LTE.", "Shows straight away which network you're on: Home or LTE.",
  "Одразу видно, яка зараз мережа: Home чи LTE.", "Қазір қай желі екені бірден көрінеді: Home не LTE.",
  "Hozir qaysi tarmoq ekani darhol ko‘rinadi: Home yoki LTE.", "Hangi ağda olduğunuz hemen görünür: Home veya LTE.",
  "بلافاصله معلوم می‌شود الان کدام شبکه است: Home یا LTE.", "立即显示当前网络：Home 还是 LTE。",
  "Muestra enseguida en qué red estás: Home o LTE.", "Zeigt sofort, welches Netz gerade da ist: Home oder LTE.")
T("settings.pingMethod", "Как проверять серверы", "How to check servers", "Як перевіряти сервери", "Серверлерді қалай тексеру",
  "Serverlarni qanday tekshirish", "Sunucular nasıl denetlensin", "روش بررسی سرورها", "如何检测服务器", "Cómo comprobar servidores",
  "Wie Server geprüft werden")
T("settings.ping.real", "Через сервер", "Through the server", "Через сервер", "Сервер арқылы", "Server orqali", "Sunucu üzerinden",
  "از طریق سرور", "经由服务器", "A través del servidor", "Über den Server")
T("settings.ping.real.hint", "Проверяет настоящую скорость через сервер. Рекомендуем.",
  "Checks the real speed through the server. Recommended.", "Перевіряє справжню швидкість через сервер. Рекомендуємо.",
  "Сервер арқылы нақты жылдамдықты тексереді. Ұсынамыз.", "Server orqali haqiqiy tezlikni tekshiradi. Tavsiya etamiz.",
  "Sunucu üzerinden gerçek hızı denetler. Önerilir.", "سرعت واقعی را از طریق سرور بررسی می‌کند. پیشنهاد می‌شود.",
  "经由服务器检测真实速度。推荐。", "Comprueba la velocidad real a través del servidor. Recomendado.",
  "Prüft die echte Geschwindigkeit über den Server. Empfohlen.")
T("settings.ping.tcp.hint", "Быстрее, но может показать сервер живым, когда он не работает.",
  "Quicker, but may show a server as alive when it doesn't work.", "Швидше, але може показати сервер живим, коли він не працює.",
  "Жылдамырақ, бірақ сервер істемесе де тірі деп көрсетуі мүмкін.", "Tezroq, lekin server ishlamasa ham uni tirik deb ko‘rsatishi mumkin.",
  "Daha hızlı, ama çalışmayan bir sunucuyu çalışıyor gösterebilir.", "سریع‌تر است، اما ممکن است سروری را که کار نمی‌کند سالم نشان دهد.",
  "更快，但可能把不能用的服务器显示为可用。", "Más rápido, pero puede mostrar como vivo un servidor que no funciona.",
  "Schneller, kann aber einen Server als lebendig zeigen, der nicht funktioniert.")
T("settings.tunnel", "Туннель", "Tunnel", "Тунель", "Туннель", "Tunnel", "Tünel", "تونل", "隧道", "Túnel", "Tunnel")
T("settings.dns", "Открывать сайты через VPN", "Look up sites through the VPN", "Відкривати сайти через VPN",
  "Сайттарды VPN арқылы ашу", "Saytlarni VPN orqali ochish", "Siteleri VPN üzerinden çöz", "باز کردن سایت‌ها از طریق VPN",
  "通过 VPN 解析网站", "Buscar sitios a través de la VPN", "Seiten über das VPN auflösen")
T("settings.dns.hint", "Адреса сайтов тоже узнаются через VPN, так меньше блокировок.",
  "Site addresses are looked up through the VPN too, so fewer things get blocked.",
  "Адреси сайтів теж визначаються через VPN, так менше блокувань.", "Сайттардың мекенжайлары да VPN арқылы анықталады, бұғаттау азаяды.",
  "Sayt manzillari ham VPN orqali aniqlanadi, bloklash kamroq bo‘ladi.", "Site adresleri de VPN üzerinden bulunur, böylece engeller azalır.",
  "نشانی سایت‌ها هم از طریق VPN پیدا می‌شود و مسدودسازی کمتر می‌شود.", "网站地址也通过 VPN 解析，被封锁的情况更少。",
  "Las direcciones de los sitios también se buscan por la VPN, así hay menos bloqueos.",
  "Auch die Adressen der Seiten werden über das VPN ermittelt, so gibt es weniger Sperren.")
T("settings.russia", "Российские сайты напрямую", "Russian sites directly", "Російські сайти напряму", "Ресей сайттары тікелей",
  "Rossiya saytlari to‘g‘ridan-to‘g‘ri", "Rus siteleri doğrudan", "سایت‌های روسی به‌طور مستقیم", "俄罗斯网站直连",
  "Sitios rusos directamente", "Russische Seiten direkt")
T("settings.russia.hint", "Сайты в зонах .ru, .su и .рф и известные российские сервисы открываются без VPN.",
  "Sites in .ru, .su and .рф and well-known Russian services open without the VPN.",
  "Сайти в зонах .ru, .su і .рф та відомі російські сервіси відкриваються без VPN.",
  ".ru, .su және .рф аймақтарындағы сайттар мен белгілі ресейлік сервистер VPN-сіз ашылады.",
  ".ru, .su va .рф zonalaridagi saytlar va mashhur Rossiya xizmatlari VPN’siz ochiladi.",
  ".ru, .su ve .рф uzantılı siteler ile bilinen Rus hizmetleri VPN’siz açılır.",
  "سایت‌های دامنه‌های ‎.ru، ‎.su و ‎.рф و سرویس‌های شناخته‌شده روسی بدون VPN باز می‌شوند.",
  ".ru、.su、.рф 域名的网站及知名俄罗斯服务不经过 VPN。",
  "Los sitios .ru, .su y .рф y los servicios rusos conocidos se abren sin VPN.",
  "Seiten unter .ru, .su und .рф sowie bekannte russische Dienste öffnen sich ohne VPN.")
T("settings.engine", "Способ передачи трафика", "How traffic is passed", "Спосіб передавання трафіку", "Трафикті жеткізу тәсілі",
  "Trafikni uzatish usuli", "Trafiğin aktarım yolu", "روش انتقال ترافیک", "流量转发方式", "Cómo se pasa el tráfico",
  "Wie der Verkehr weitergegeben wird")
T("settings.engine.main", "Основной", "Main", "Основний", "Негізгі", "Asosiy", "Ana", "اصلی", "主要", "Principal", "Haupt")
T("settings.engine.spare", "Запасной", "Spare", "Запасний", "Қосалқы", "Zaxira", "Yedek", "پشتیبان", "备用", "Alternativo", "Ersatz")
T("settings.engine.hint", "Если VPN включается, но сайты не открываются, попробуйте другой способ.",
  "If the VPN turns on but sites don't open, try the other way.", "Якщо VPN вмикається, але сайти не відкриваються, спробуйте інший спосіб.",
  "VPN қосылып, бірақ сайттар ашылмаса, басқа тәсілді көріңіз.", "VPN yoqilsa-yu, saytlar ochilmasa, boshqa usulni sinab ko‘ring.",
  "VPN açılıyor ama siteler açılmıyorsa diğer yolu deneyin.", "اگر VPN روشن می‌شود ولی سایت‌ها باز نمی‌شوند، روش دیگر را امتحان کنید.",
  "如果 VPN 能开启但网站打不开，请换另一种方式。", "Si la VPN se activa pero los sitios no abren, prueba la otra forma.",
  "Wenn das VPN angeht, aber Seiten nicht laden, probiere den anderen Weg.")
T("settings.applyOnReconnect", "Изменения заработают при следующем подключении", "Changes apply on the next connection",
  "Зміни запрацюють під час наступного підключення", "Өзгерістер келесі қосылғанда іске асады", "O‘zgarishlar keyingi ulanishda ishlaydi",
  "Değişiklikler bir sonraki bağlantıda geçerli olur", "تغییرات در اتصال بعدی اعمال می‌شوند", "更改将在下次连接时生效",
  "Los cambios se aplican en la próxima conexión", "Änderungen gelten ab der nächsten Verbindung")
T("settings.look", "Оформление", "Look", "Оформлення", "Безендіру", "Ko‘rinish", "Görünüm", "ظاهر", "外观", "Aspecto", "Aussehen")
T("settings.theme", "Тема", "Theme", "Тема", "Тақырып", "Mavzu", "Tema", "پوسته", "主题", "Tema", "Design")
T("theme.system", "Система", "System", "Система", "Жүйе", "Tizim", "Sistem", "سیستم", "跟随系统", "Sistema", "System")
T("theme.light", "Светлая", "Light", "Світла", "Ашық", "Yorug‘", "Açık", "روشن", "浅色", "Clara", "Hell")
T("theme.ocean", "Океан", "Ocean", "Океан", "Мұхит", "Okean", "Okyanus", "اقیانوس", "海洋", "Océano", "Ozean")
T("theme.ember", "Уголь", "Ember", "Вугілля", "Көмір", "Ko‘mir", "Kor", "زغال", "余烬", "Brasa", "Glut")
T("settings.language", "Язык", "Language", "Мова", "Тіл", "Til", "Dil", "زبان", "语言", "Idioma", "Sprache")
T("settings.language.row", "Язык приложения", "App language", "Мова застосунку", "Қолданба тілі", "Ilova tili", "Uygulama dili",
  "زبان برنامه", "应用语言", "Idioma de la app", "App-Sprache")
T("settings.language.hint", "Сейчас: %@. Откроются настройки iPhone — выберите там пункт «Язык».",
  "Now: %@. The iPhone settings open — choose “Language” there.",
  "Зараз: %@. Відкриються налаштування iPhone — виберіть там пункт «Мова».",
  "Қазір: %@. iPhone баптаулары ашылады — сол жерден «Тіл» тармағын таңдаңыз.",
  "Hozir: %@. iPhone sozlamalari ochiladi — u yerda “Til” bandini tanlang.",
  "Şu an: %@. iPhone ayarları açılır — orada “Dil”i seçin.",
  "اکنون: %@. تنظیمات iPhone باز می‌شود؛ آنجا «زبان» را انتخاب کنید.",
  "当前：%@。将打开 iPhone 设置，请在其中选择“语言”。",
  "Ahora: %@. Se abren los ajustes del iPhone: elige «Idioma».",
  "Jetzt: %@. Die iPhone-Einstellungen öffnen sich – wähle dort „Sprache“.")
T("settings.device", "Устройство", "Device", "Пристрій", "Құрылғы", "Qurilma", "Cihaz", "دستگاه", "设备", "Dispositivo", "Gerät")
T("settings.hwid", "Привязка к устройству", "Device binding", "Прив’язка до пристрою", "Құрылғыға байлау", "Qurilmaga bog‘lash",
  "Cihaza bağlama", "اتصال به دستگاه", "绑定设备", "Vinculación al dispositivo", "Gerätebindung")
T("settings.hwid.hint", "Включите, если VPN-сервис ограничивает число устройств на одну подписку.",
  "Turn on if your VPN service limits devices per subscription.",
  "Увімкніть, якщо VPN-сервіс обмежує кількість пристроїв на одну підписку.",
  "VPN-сервис бір жазылымға құрылғы санын шектесе, қосыңыз.",
  "VPN xizmati bitta obunaga qurilmalar sonini cheklasa, yoqing.",
  "VPN hizmetiniz abonelik başına cihaz sayısını sınırlıyorsa açın.",
  "اگر سرویس VPN تعداد دستگاه‌های هر اشتراک را محدود می‌کند، روشن کنید.",
  "如果 VPN 服务限制每个订阅的设备数量，请开启。",
  "Actívalo si tu servicio VPN limita los dispositivos por suscripción.",
  "Schalte es ein, wenn dein VPN-Dienst die Geräte pro Abo begrenzt.")
T("settings.hwid.code", "Код этого устройства", "This device's code", "Код цього пристрою", "Осы құрылғының коды",
  "Ushbu qurilma kodi", "Bu cihazın kodu", "کد این دستگاه", "本设备代码", "Código de este dispositivo", "Code dieses Geräts")
T("settings.hwid.copy", "Скопировать код", "Copy the code", "Скопіювати код", "Кодты көшіру", "Kodni nusxalash", "Kodu kopyala",
  "کپی کد", "复制代码", "Copiar el código", "Code kopieren")
T("settings.hwid.copied", "Код скопирован", "Code copied", "Код скопійовано", "Код көшірілді", "Kod nusxalandi", "Kod kopyalandı",
  "کد کپی شد", "代码已复制", "Código copiado", "Code kopiert")
T("settings.about", "О приложении", "About", "Про застосунок", "Қолданба туралы", "Ilova haqida", "Hakkında", "درباره", "关于",
  "Acerca de", "Über die App")
T("settings.version", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@")
T("settings.core", "Ядро Xray %@", "Xray core %@", "Ядро Xray %@", "Xray ядросы %@", "Xray yadrosi %@", "Xray çekirdeği %@",
  "هسته Xray %@", "Xray 核心 %@", "Núcleo Xray %@", "Xray-Kern %@")
T("settings.help", "Помощь и инструкции", "Help and guides", "Допомога та інструкції", "Көмек пен нұсқаулар", "Yordam va qo‘llanmalar",
  "Yardım ve kılavuzlar", "راهنما و آموزش", "帮助与说明", "Ayuda e instrucciones", "Hilfe und Anleitungen")
T("settings.help.hint", "Как добавить подписку и выбрать режим", "How to add a subscription and pick a mode",
  "Як додати підписку і вибрати режим", "Жазылымды қалай қосып, режимді таңдау керек", "Obunani qanday qo‘shish va rejimni tanlash",
  "Abonelik ekleme ve mod seçme", "چگونه اشتراک اضافه کنیم و حالت را انتخاب کنیم", "如何添加订阅并选择模式",
  "Cómo añadir una suscripción y elegir un modo", "Wie man ein Abo hinzufügt und einen Modus wählt")
T("settings.source", "Исходный код", "Source code", "Вихідний код", "Бастапқы код", "Manba kodi", "Kaynak kodu", "کد منبع",
  "源代码", "Código fuente", "Quellcode")
T("settings.signingNote",
  "Volta для iPhone распространяется не через App Store. VPN работает только в сборке, подписанной платным аккаунтом разработчика Apple.",
  "Volta for iPhone isn't distributed through the App Store. The VPN only works in a build signed with a paid Apple developer account.",
  "Volta для iPhone поширюється не через App Store. VPN працює лише у збірці, підписаній платним акаунтом розробника Apple.",
  "iPhone-ға арналған Volta App Store арқылы таратылмайды. VPN тек Apple-дің ақылы әзірлеуші аккаунтымен қол қойылған жинақта жұмыс істейді.",
  "iPhone uchun Volta App Store orqali tarqatilmaydi. VPN faqat Apple’ning pullik dasturchi akkaunti bilan imzolangan yig‘mada ishlaydi.",
  "iPhone için Volta App Store üzerinden dağıtılmaz. VPN yalnızca ücretli Apple geliştirici hesabıyla imzalanmış sürümde çalışır.",
  "Volta برای iPhone از طریق App Store منتشر نمی‌شود. VPN فقط در نسخه‌ای کار می‌کند که با حساب پولی توسعه‌دهنده Apple امضا شده باشد.",
  "iPhone 版 Volta 不通过 App Store 发布。只有使用付费 Apple 开发者账号签名的版本才能使用 VPN。",
  "Volta para iPhone no se distribuye por la App Store. La VPN solo funciona en una versión firmada con una cuenta de desarrollador de Apple de pago.",
  "Volta für iPhone gibt es nicht im App Store. Das VPN funktioniert nur in einem Build, der mit einem bezahlten Apple-Entwicklerkonto signiert ist.")

# ------------------------------------------------------------------ servers (core errors)
T("server.error.incomplete", "В ссылке на сервер не хватает данных", "The server link is incomplete",
  "У посиланні на сервер бракує даних", "Сервер сілтемесінде деректер жетпейді", "Server havolasida ma’lumot yetishmaydi",
  "Sunucu bağlantısı eksik", "لینک سرور ناقص است", "服务器链接不完整", "Al enlace del servidor le faltan datos",
  "Im Server-Link fehlen Angaben")
T("server.error.notInThisBuild", "%@ пока не работает в Volta для iPhone", "%@ doesn't work in Volta for iPhone yet",
  "%@ поки не працює у Volta для iPhone", "%@ әзірге iPhone-ға арналған Volta-да жұмыс істемейді",
  "%@ hozircha iPhone uchun Volta’da ishlamaydi", "%@ iPhone için Volta’da henüz çalışmıyor",
  "%@ فعلاً در Volta برای iPhone کار نمی‌کند", "%@ 暂不支持 iPhone 版 Volta", "%@ aún no funciona en Volta para iPhone",
  "%@ funktioniert in Volta für iPhone noch nicht")
T("server.error.unknownType", "«%@»: Volta не знает такой тип сервера", "“%@”: Volta doesn't know this kind of server",
  "«%@»: Volta не знає такого типу сервера", "«%@»: Volta мұндай сервер түрін білмейді", "“%@”: Volta bunday server turini bilmaydi",
  "“%@”: Volta bu tür sunucuyu tanımıyor", "«%@»: Volta این نوع سرور را نمی‌شناسد", "“%@”：Volta 不认识这种服务器",
  "«%@»: Volta no conoce este tipo de servidor", "„%@“: Volta kennt diese Art Server nicht")
T("server.error.typeNotSupported", "«%@»: такой тип сервера Volta пока не поддерживает",
  "“%@”: Volta doesn't support this kind of server yet", "«%@»: такий тип сервера Volta поки не підтримує",
  "«%@»: Volta мұндай сервер түрін әзірге қолдамайды", "“%@”: Volta bunday server turini hozircha qo‘llamaydi",
  "“%@”: Volta bu tür sunucuyu henüz desteklemiyor", "«%@»: Volta فعلاً از این نوع سرور پشتیبانی نمی‌کند",
  "“%@”：Volta 暂不支持这种服务器", "«%@»: Volta aún no admite este tipo de servidor", "„%@“: Volta unterstützt diese Art Server noch nicht")
T("server.error.noKey", "«%@»: в ссылке нет ключа доступа", "“%@”: the link has no access key", "«%@»: у посиланні немає ключа доступу",
  "«%@»: сілтемеде кіру кілті жоқ", "“%@”: havolada kirish kaliti yo‘q", "“%@”: bağlantıda erişim anahtarı yok",
  "«%@»: لینک کلید دسترسی ندارد", "“%@”：链接中没有访问密钥", "«%@»: el enlace no tiene clave de acceso", "„%@“: Im Link fehlt der Zugangsschlüssel")
T("server.error.noKeys", "«%@»: в настройках сервера нет ключей", "“%@”: the server settings have no keys",
  "«%@»: у налаштуваннях сервера немає ключів", "«%@»: сервер баптауларында кілттер жоқ", "“%@”: server sozlamalarida kalitlar yo‘q",
  "“%@”: sunucu ayarlarında anahtar yok", "«%@»: تنظیمات سرور کلید ندارد", "“%@”：服务器配置中没有密钥",
  "«%@»: los ajustes del servidor no tienen claves", "„%@“: In den Server-Einstellungen fehlen die Schlüssel")
T("server.error.oldTransport", "«%@»: сервер использует устаревший способ подключения, попросите у сервиса новую ссылку",
  "“%@”: the server uses an outdated connection method, ask the service for a new link",
  "«%@»: сервер використовує застарілий спосіб підключення, попросіть у сервісу нове посилання",
  "«%@»: сервер ескірген қосылу тәсілін қолданады, сервистен жаңа сілтеме сұраңыз",
  "“%@”: server eskirgan ulanish usulidan foydalanadi, xizmatdan yangi havola so‘rang",
  "“%@”: sunucu eski bir bağlantı yöntemi kullanıyor, hizmetten yeni bağlantı isteyin",
  "«%@»: سرور از روش اتصال قدیمی استفاده می‌کند، از سرویس لینک تازه بخواهید",
  "“%@”：服务器使用过时的连接方式，请向服务商索取新链接",
  "«%@»: el servidor usa un método de conexión antiguo, pide al servicio un enlace nuevo",
  "„%@“: Der Server nutzt eine veraltete Verbindungsart, bitte den Dienst um einen neuen Link")
T("server.error.encryption", "«%@»: сервер использует шифрование, которое Volta не поддерживает",
  "“%@”: the server uses encryption Volta doesn't support", "«%@»: сервер використовує шифрування, яке Volta не підтримує",
  "«%@»: сервер Volta қолдамайтын шифрлауды қолданады", "“%@”: server Volta qo‘llamaydigan shifrlashdan foydalanadi",
  "“%@”: sunucu Volta’nın desteklemediği bir şifreleme kullanıyor", "«%@»: سرور از رمزنگاری‌ای استفاده می‌کند که Volta پشتیبانی نمی‌کند",
  "“%@”：服务器使用了 Volta 不支持的加密方式", "«%@»: el servidor usa un cifrado que Volta no admite",
  "„%@“: Der Server nutzt eine Verschlüsselung, die Volta nicht unterstützt")
T("server.error.noRealityKey", "«%@»: в ссылке не хватает ключа, попросите у сервиса новую",
  "“%@”: the link is missing a key, ask the service for a new one", "«%@»: у посиланні бракує ключа, попросіть у сервісу нове",
  "«%@»: сілтемеде кілт жетпейді, сервистен жаңасын сұраңыз", "“%@”: havolada kalit yetishmaydi, xizmatdan yangisini so‘rang",
  "“%@”: bağlantıda bir anahtar eksik, hizmetten yenisini isteyin", "«%@»: لینک یک کلید کم دارد، از سرویس لینک تازه بخواهید",
  "“%@”：链接缺少密钥，请向服务商索取新链接", "«%@»: al enlace le falta una clave, pide uno nuevo al servicio",
  "„%@“: Im Link fehlt ein Schlüssel, bitte den Dienst um einen neuen")
T("server.error.noPassword", "«%@»: в ссылке нет пароля", "“%@”: the link has no password", "«%@»: у посиланні немає пароля",
  "«%@»: сілтемеде құпиясөз жоқ", "“%@”: havolada parol yo‘q", "“%@”: bağlantıda parola yok", "«%@»: لینک رمز عبور ندارد",
  "“%@”：链接中没有密码", "«%@»: el enlace no tiene contraseña", "„%@“: Im Link fehlt das Passwort")

# ------------------------------------------------------------------ links
T("link.noAddress", "В ссылке нет адреса подписки", "The link has no subscription address", "У посиланні немає адреси підписки",
  "Сілтемеде жазылым мекенжайы жоқ", "Havolada obuna manzili yo‘q", "Bağlantıda abonelik adresi yok", "لینک نشانی اشتراک ندارد",
  "链接中没有订阅地址", "El enlace no tiene dirección de suscripción", "Im Link fehlt die Abo-Adresse")
T("link.crypt.empty", "Зашифрованная ссылка пустая", "The encrypted link is empty", "Зашифроване посилання порожнє",
  "Шифрланған сілтеме бос", "Shifrlangan havola bo‘sh", "Şifreli bağlantı boş", "لینک رمزنگاری‌شده خالی است", "加密链接是空的",
  "El enlace cifrado está vacío", "Der verschlüsselte Link ist leer")
T("link.crypt.damaged", "Зашифрованная ссылка повреждена", "The encrypted link is damaged", "Зашифроване посилання пошкоджене",
  "Шифрланған сілтеме зақымдалған", "Shifrlangan havola buzilgan", "Şifreli bağlantı bozuk", "لینک رمزنگاری‌شده خراب است",
  "加密链接已损坏", "El enlace cifrado está dañado", "Der verschlüsselte Link ist beschädigt")
T("link.crypt.cut", "Зашифрованная ссылка обрезана", "The encrypted link is cut short", "Зашифроване посилання обрізане",
  "Шифрланған сілтеме қиылған", "Shifrlangan havola qirqilgan", "Şifreli bağlantı yarım kalmış", "لینک رمزنگاری‌شده ناقص است",
  "加密链接不完整", "El enlace cifrado está cortado", "Der verschlüsselte Link ist abgeschnitten")
T("link.crypt.newer", "Ссылка зашифрована для более новой версии Volta — обновите приложение",
  "The link is encrypted for a newer Volta — update the app", "Посилання зашифроване для новішої версії Volta — оновіть застосунок",
  "Сілтеме Volta-ның жаңарақ нұсқасына шифрланған — қолданбаны жаңартыңыз",
  "Havola Volta’ning yangiroq versiyasi uchun shifrlangan — ilovani yangilang",
  "Bağlantı Volta’nın daha yeni bir sürümü için şifrelenmiş — uygulamayı güncelleyin",
  "لینک برای نسخه جدیدتر Volta رمزنگاری شده است؛ برنامه را به‌روز کنید", "该链接是为更新版本的 Volta 加密的，请更新应用",
  "El enlace está cifrado para una versión más nueva de Volta: actualiza la app",
  "Der Link ist für eine neuere Volta verschlüsselt – aktualisiere die App")
T("link.crypt.unknownKey", "Ссылка зашифрована ключом, которого нет в этой версии Volta",
  "The link is encrypted with a key this version of Volta doesn't have", "Посилання зашифроване ключем, якого немає в цій версії Volta",
  "Сілтеме Volta-ның бұл нұсқасында жоқ кілтпен шифрланған", "Havola Volta’ning bu versiyasida yo‘q kalit bilan shifrlangan",
  "Bağlantı, Volta’nın bu sürümünde olmayan bir anahtarla şifrelenmiş", "لینک با کلیدی رمزنگاری شده که این نسخه Volta ندارد",
  "该链接使用了此版本 Volta 没有的密钥加密", "El enlace está cifrado con una clave que esta versión de Volta no tiene",
  "Der Link ist mit einem Schlüssel verschlüsselt, den diese Volta-Version nicht hat")
T("link.crypt.failed", "Не удалось расшифровать ссылку: она повреждена или не для Volta",
  "Couldn't decrypt the link: it is damaged or not meant for Volta", "Не вдалося розшифрувати посилання: воно пошкоджене або не для Volta",
  "Сілтемені шифрдан шығару мүмкін болмады: ол зақымдалған не Volta-ға арналмаған",
  "Havolani deshifrlab bo‘lmadi: u buzilgan yoki Volta uchun emas",
  "Bağlantının şifresi çözülemedi: bozuk ya da Volta için değil", "رمزگشایی لینک ممکن نشد: خراب است یا برای Volta نیست",
  "无法解密链接：链接已损坏或不是给 Volta 的", "No se pudo descifrar el enlace: está dañado o no es para Volta",
  "Der Link ließ sich nicht entschlüsseln: Er ist beschädigt oder nicht für Volta")
T("link.crypt.twice", "Ссылка зашифрована дважды", "The link is encrypted twice", "Посилання зашифроване двічі",
  "Сілтеме екі рет шифрланған", "Havola ikki marta shifrlangan", "Bağlantı iki kez şifrelenmiş", "لینک دو بار رمزنگاری شده است",
  "链接被加密了两次", "El enlace está cifrado dos veces", "Der Link ist doppelt verschlüsselt")
T("link.crypt.noSubscription", "В зашифрованной ссылке нет подписки", "The encrypted link holds no subscription",
  "У зашифрованому посиланні немає підписки", "Шифрланған сілтемеде жазылым жоқ", "Shifrlangan havolada obuna yo‘q",
  "Şifreli bağlantıda abonelik yok", "لینک رمزنگاری‌شده اشتراکی ندارد", "加密链接中没有订阅",
  "El enlace cifrado no contiene una suscripción", "Der verschlüsselte Link enthält kein Abo")
T("link.crypt.noAddress", "В зашифрованной ссылке нет адреса подписки", "The encrypted link has no subscription address",
  "У зашифрованому посиланні немає адреси підписки", "Шифрланған сілтемеде жазылым мекенжайы жоқ",
  "Shifrlangan havolada obuna manzili yo‘q", "Şifreli bağlantıda abonelik adresi yok", "لینک رمزنگاری‌شده نشانی اشتراک ندارد",
  "加密链接中没有订阅地址", "El enlace cifrado no tiene dirección de suscripción", "Im verschlüsselten Link fehlt die Abo-Adresse")

# ------------------------------------------------------------------ subscriptions (model)
T("sub.defaultName", "Подписка", "Subscription", "Підписка", "Жазылым", "Obuna", "Abonelik", "اشتراک", "订阅", "Suscripción", "Abo")
T("sub.added", "Подписка «%@» добавлена", "Subscription “%@” added", "Підписку «%@» додано", "«%@» жазылымы қосылды",
  "“%@” obunasi qo‘shildi", "“%@” aboneliği eklendi", "اشتراک «%@» اضافه شد", "已添加订阅“%@”", "Suscripción «%@» añadida",
  "Abo „%@“ hinzugefügt")
T("sub.refreshed", "Подписка обновлена", "Subscription updated", "Підписку оновлено", "Жазылым жаңартылды", "Obuna yangilandi",
  "Abonelik güncellendi", "اشتراک به‌روز شد", "订阅已更新", "Suscripción actualizada", "Abo aktualisiert")
T("sub.refreshedAll", "Подписки обновлены", "Subscriptions updated", "Підписки оновлено", "Жазылымдар жаңартылды",
  "Obunalar yangilandi", "Abonelikler güncellendi", "اشتراک‌ها به‌روز شدند", "订阅已全部更新", "Suscripciones actualizadas",
  "Abos aktualisiert")
T("sub.refreshFailed", "Не обновилось подписок: %d", "Subscriptions not updated: %d", "Не оновилося підписок: %d",
  "Жаңармаған жазылымдар: %d", "Yangilanmagan obunalar: %d", "Güncellenemeyen abonelik: %d", "اشتراک‌های به‌روزنشده: %d",
  "未能更新的订阅：%d", "Suscripciones sin actualizar: %d", "Nicht aktualisierte Abos: %d")
T("sub.error.noLink", "Вставьте ссылку на подписку", "Paste a subscription link", "Вставте посилання на підписку",
  "Жазылым сілтемесін қойыңыз", "Obuna havolasini qo‘ying", "Bir abonelik bağlantısı yapıştırın", "لینک اشتراک را بچسبانید",
  "请粘贴订阅链接", "Pega el enlace de una suscripción", "Füge einen Abo-Link ein")
T("sub.error.https", "Используйте защищённую HTTPS-ссылку на подписку", "Use a secure HTTPS subscription link",
  "Використовуйте захищене HTTPS-посилання на підписку", "Қорғалған HTTPS жазылым сілтемесін қолданыңыз",
  "Himoyalangan HTTPS obuna havolasidan foydalaning", "Güvenli bir HTTPS abonelik bağlantısı kullanın",
  "از لینک امن HTTPS برای اشتراک استفاده کنید", "请使用安全的 HTTPS 订阅链接", "Usa un enlace de suscripción HTTPS seguro",
  "Nutze einen sicheren HTTPS-Abo-Link")
T("sub.error.badLink", "Это не похоже на ссылку", "This doesn't look like a link", "Це не схоже на посилання",
  "Бұл сілтемеге ұқсамайды", "Bu havolaga o‘xshamaydi", "Bu bir bağlantıya benzemiyor", "این شبیه لینک نیست", "这看起来不像链接",
  "Esto no parece un enlace", "Das sieht nicht wie ein Link aus")
T("sub.error.noConnection", "Нет связи с сервером подписки. Проверьте интернет.",
  "Can't reach the subscription server. Check the internet.", "Немає зв’язку з сервером підписки. Перевірте інтернет.",
  "Жазылым серверімен байланыс жоқ. Интернетті тексеріңіз.", "Obuna serveri bilan aloqa yo‘q. Internetni tekshiring.",
  "Abonelik sunucusuna ulaşılamıyor. İnterneti kontrol edin.", "ارتباط با سرور اشتراک برقرار نیست. اینترنت را بررسی کنید.",
  "无法连接订阅服务器。请检查网络。", "No hay conexión con el servidor de la suscripción. Revisa internet.",
  "Der Abo-Server ist nicht erreichbar. Prüfe das Internet.")
T("sub.error.timeout", "Сервер подписки не ответил вовремя.", "The subscription server didn't answer in time.",
  "Сервер підписки не відповів вчасно.", "Жазылым сервері уақытында жауап бермеді.", "Obuna serveri o‘z vaqtida javob bermadi.",
  "Abonelik sunucusu zamanında yanıt vermedi.", "سرور اشتراک به‌موقع پاسخ نداد.", "订阅服务器未及时响应。",
  "El servidor de la suscripción no respondió a tiempo.", "Der Abo-Server hat nicht rechtzeitig geantwortet.")
T("sub.error.tls", "Не удалось установить защищённое соединение с сервером подписки.",
  "Couldn't set up a secure connection to the subscription server.", "Не вдалося встановити захищене з’єднання із сервером підписки.",
  "Жазылым серверімен қорғалған байланыс орнату мүмкін болмады.", "Obuna serveri bilan himoyalangan aloqa o‘rnatib bo‘lmadi.",
  "Abonelik sunucusuyla güvenli bağlantı kurulamadı.", "برقراری اتصال امن با سرور اشتراک ممکن نشد.", "无法与订阅服务器建立安全连接。",
  "No se pudo establecer una conexión segura con el servidor de la suscripción.",
  "Keine sichere Verbindung zum Abo-Server möglich.")
T("sub.error.download", "Не удалось загрузить подписку. Проверьте интернет.", "Couldn't download the subscription. Check the internet.",
  "Не вдалося завантажити підписку. Перевірте інтернет.", "Жазылымды жүктеу мүмкін болмады. Интернетті тексеріңіз.",
  "Obunani yuklab bo‘lmadi. Internetni tekshiring.", "Abonelik indirilemedi. İnterneti kontrol edin.",
  "دانلود اشتراک ممکن نشد. اینترنت را بررسی کنید.", "无法下载订阅。请检查网络。", "No se pudo descargar la suscripción. Revisa internet.",
  "Das Abo ließ sich nicht laden. Prüfe das Internet.")
T("sub.error.status", "Сервис не отдал подписку (ошибка %d). Проверьте ссылку или срок подписки.",
  "The service didn't return the subscription (error %d). Check the link or the subscription term.",
  "Сервіс не віддав підписку (помилка %d). Перевірте посилання або термін підписки.",
  "Сервис жазылымды бермеді (қате %d). Сілтемені не жазылым мерзімін тексеріңіз.",
  "Xizmat obunani bermadi (xato %d). Havola yoki obuna muddatini tekshiring.",
  "Hizmet aboneliği vermedi (hata %d). Bağlantıyı veya abonelik süresini kontrol edin.",
  "سرویس اشتراک را نداد (خطای %d). لینک یا مدت اشتراک را بررسی کنید.",
  "服务没有返回订阅（错误 %d）。请检查链接或订阅期限。",
  "El servicio no devolvió la suscripción (error %d). Revisa el enlace o la vigencia.",
  "Der Dienst hat das Abo nicht geliefert (Fehler %d). Prüfe den Link oder die Laufzeit.")
T("sub.error.empty", "Подписка пустая", "The subscription is empty", "Підписка порожня", "Жазылым бос", "Obuna bo‘sh",
  "Abonelik boş", "اشتراک خالی است", "订阅是空的", "La suscripción está vacía", "Das Abo ist leer")
T("sub.error.noServers",
  "В подписке не нашлось ни одного сервера. Проверьте ссылку или напишите в поддержку VPN-сервиса.",
  "No servers found in the subscription. Check the link or contact the VPN service.",
  "У підписці не знайшлося жодного сервера. Перевірте посилання або напишіть у підтримку VPN-сервісу.",
  "Жазылымда бірде-бір сервер табылмады. Сілтемені тексеріңіз не VPN-сервис қолдауына жазыңыз.",
  "Obunada birorta ham server topilmadi. Havolani tekshiring yoki VPN xizmati yordamiga yozing.",
  "Abonelikte hiç sunucu bulunamadı. Bağlantıyı kontrol edin veya VPN hizmetinin desteğine yazın.",
  "در اشتراک هیچ سروری پیدا نشد. لینک را بررسی کنید یا به پشتیبانی سرویس VPN پیام دهید.",
  "订阅中没有找到任何服务器。请检查链接或联系 VPN 服务的客服。",
  "No se encontró ningún servidor en la suscripción. Revisa el enlace o escribe al soporte del servicio VPN.",
  "Im Abo wurde kein Server gefunden. Prüfe den Link oder schreib dem Support des VPN-Dienstes.")
T("sub.error.onlyUnsupported", "В подписке только серверы, которые Volta для iPhone пока не открывает: %@",
  "The subscription only has servers Volta for iPhone can't open yet: %@",
  "У підписці лише сервери, які Volta для iPhone поки не відкриває: %@",
  "Жазылымда тек iPhone-ға арналған Volta әзірге аша алмайтын серверлер бар: %@",
  "Obunada faqat iPhone uchun Volta hozircha ocha olmaydigan serverlar bor: %@",
  "Abonelikte yalnızca iPhone için Volta’nın henüz açamadığı sunucular var: %@",
  "اشتراک فقط سرورهایی دارد که Volta برای iPhone فعلاً باز نمی‌کند: %@",
  "订阅中只有 iPhone 版 Volta 暂时无法打开的服务器：%@",
  "La suscripción solo tiene servidores que Volta para iPhone aún no abre: %@",
  "Das Abo enthält nur Server, die Volta für iPhone noch nicht öffnet: %@")

# ------------------------------------------------------------------ import (model)
T("import.error.amnezia", "Ключи AmneziaVPN пока не работают в Volta для iPhone",
  "AmneziaVPN keys don't work in Volta for iPhone yet", "Ключі AmneziaVPN поки не працюють у Volta для iPhone",
  "AmneziaVPN кілттері әзірге iPhone-ға арналған Volta-да жұмыс істемейді", "AmneziaVPN kalitlari hozircha iPhone uchun Volta’da ishlamaydi",
  "AmneziaVPN anahtarları iPhone için Volta’da henüz çalışmıyor", "کلیدهای AmneziaVPN فعلاً در Volta برای iPhone کار نمی‌کنند",
  "iPhone 版 Volta 暂不支持 AmneziaVPN 密钥", "Las claves de AmneziaVPN aún no funcionan en Volta para iPhone",
  "AmneziaVPN-Schlüssel funktionieren in Volta für iPhone noch nicht")
T("import.error.nothing", "Не нашлось ни одного сервера. Проверьте, что скопировали ссылку целиком.",
  "No servers found. Check that you copied the whole link.", "Не знайшлося жодного сервера. Перевірте, що скопіювали посилання повністю.",
  "Бірде-бір сервер табылмады. Сілтемені толық көшіргеніңізді тексеріңіз.",
  "Birorta ham server topilmadi. Havolani to‘liq nusxalaganingizni tekshiring.",
  "Hiç sunucu bulunamadı. Bağlantının tamamını kopyaladığınızdan emin olun.",
  "هیچ سروری پیدا نشد. بررسی کنید که لینک را کامل کپی کرده‌اید.", "没有找到任何服务器。请确认已完整复制链接。",
  "No se encontró ningún servidor. Comprueba que copiaste el enlace entero.",
  "Kein Server gefunden. Prüfe, ob du den ganzen Link kopiert hast.")
T("import.error.unsupported", "Такие серверы Volta для iPhone пока не открывает: %@",
  "Volta for iPhone can't open these servers yet: %@", "Такі сервери Volta для iPhone поки не відкриває: %@",
  "Мұндай серверлерді iPhone-ға арналған Volta әзірге ашпайды: %@", "Bunday serverlarni iPhone uchun Volta hozircha ochmaydi: %@",
  "iPhone için Volta bu sunucuları henüz açamıyor: %@", "Volta برای iPhone فعلاً این سرورها را باز نمی‌کند: %@",
  "iPhone 版 Volta 暂时无法打开这些服务器：%@", "Volta para iPhone aún no abre estos servidores: %@",
  "Volta für iPhone öffnet diese Server noch nicht: %@")
T("import.already", "Эти серверы уже есть в списке", "These servers are already in the list", "Ці сервери вже є у списку",
  "Бұл серверлер тізімде бар", "Bu serverlar ro‘yxatda allaqachon bor", "Bu sunucular zaten listede",
  "این سرورها از قبل در فهرست هستند", "这些服务器已在列表中", "Estos servidores ya están en la lista", "Diese Server sind schon in der Liste")
P("import.added",
  ("Добавлен %d сервер", "Добавлено %d сервера", "Добавлено %d серверов"),
  ("Added %d server", "Added %d servers"),
  ("Додано %d сервер", "Додано %d сервери", "Додано %d серверів"),
  ("%d сервер қосылды", "%d сервер қосылды"),
  ("%d ta server qo‘shildi", "%d ta server qo‘shildi"),
  ("%d sunucu eklendi", "%d sunucu eklendi"),
  ("%d سرور اضافه شد", "%d سرور اضافه شد"),
  ("已添加 %d 个服务器", "已添加 %d 个服务器"),
  ("Añadido %d servidor", "Añadidos %d servidores"),
  ("%d Server hinzugefügt", "%d Server hinzugefügt"))

# ------------------------------------------------------------------ tunnel and VPN profile
T("tunnel.error.noPlan", "Нет настроек подключения. Включите VPN из приложения Volta.",
  "No connection settings. Turn the VPN on from the Volta app.", "Немає налаштувань підключення. Увімкніть VPN із застосунку Volta.",
  "Қосылу баптаулары жоқ. VPN-ді Volta қолданбасынан қосыңыз.", "Ulanish sozlamalari yo‘q. VPN’ni Volta ilovasidan yoqing.",
  "Bağlantı ayarı yok. VPN’i Volta uygulamasından açın.", "تنظیمات اتصال نیست. VPN را از برنامه Volta روشن کنید.",
  "没有连接设置。请从 Volta 应用中开启 VPN。", "No hay ajustes de conexión. Activa la VPN desde la app Volta.",
  "Keine Verbindungsdaten. Schalte das VPN in der Volta-App ein.")
T("tunnel.error.settings", "iOS не приняла настройки VPN: %@", "iOS didn't accept the VPN settings: %@",
  "iOS не прийняла налаштування VPN: %@", "iOS VPN баптауларын қабылдамады: %@", "iOS VPN sozlamalarini qabul qilmadi: %@",
  "iOS VPN ayarlarını kabul etmedi: %@", "iOS تنظیمات VPN را نپذیرفت: %@", "iOS 未接受 VPN 设置：%@",
  "iOS no aceptó los ajustes de la VPN: %@", "iOS hat die VPN-Einstellungen nicht angenommen: %@")
T("tunnel.error.noInterface", "Не удалось подключиться к VPN-интерфейсу iOS", "Couldn't attach to the iOS VPN interface",
  "Не вдалося підключитися до VPN-інтерфейсу iOS", "iOS VPN интерфейсіне қосылу мүмкін болмады",
  "iOS VPN interfeysiga ulanib bo‘lmadi", "iOS VPN arayüzüne bağlanılamadı", "اتصال به رابط VPN در iOS ممکن نشد",
  "无法接入 iOS 的 VPN 接口", "No se pudo conectar con la interfaz VPN de iOS", "Keine Verbindung zur VPN-Schnittstelle von iOS")
T("tunnel.error.core", "Ядро Xray не запустилось: %@", "The Xray core didn't start: %@", "Ядро Xray не запустилося: %@",
  "Xray ядросы іске қосылмады: %@", "Xray yadrosi ishga tushmadi: %@", "Xray çekirdeği başlamadı: %@", "هسته Xray اجرا نشد: %@",
  "Xray 核心未能启动：%@", "El núcleo Xray no arrancó: %@", "Der Xray-Kern ist nicht gestartet: %@")
T("tunnel.error.bridge", "Передача трафика остановилась (код %d)", "Traffic forwarding stopped (code %d)",
  "Передавання трафіку зупинилося (код %d)", "Трафикті жеткізу тоқтады (код %d)", "Trafik uzatish to‘xtadi (kod %d)",
  "Trafik aktarımı durdu (kod %d)", "انتقال ترافیک متوقف شد (کد %d)", "流量转发已停止（代码 %d）",
  "El reenvío de tráfico se detuvo (código %d)", "Die Weiterleitung ist stehen geblieben (Code %d)")
T("vpn.error.config", "Профиль VPN испорчен. Удалите «Volta» в Настройках → VPN и подключитесь снова.",
  "The VPN profile is broken. Delete “Volta” in Settings → VPN and connect again.",
  "Профіль VPN зіпсовано. Видаліть «Volta» в Налаштуваннях → VPN і підключіться знову.",
  "VPN профилі бұзылған. Баптаулар → VPN бөлімінде «Volta»-ны жойып, қайта қосылыңыз.",
  "VPN profili buzilgan. Sozlamalar → VPN bo‘limida “Volta”ni o‘chirib, qayta ulaning.",
  "VPN profili bozuk. Ayarlar → VPN’de “Volta”yı silip yeniden bağlanın.",
  "نمایه VPN خراب است. «Volta» را در تنظیمات ← VPN حذف کنید و دوباره وصل شوید.",
  "VPN 配置已损坏。请在“设置 → VPN”中删除“Volta”后重新连接。",
  "El perfil VPN está dañado. Borra «Volta» en Ajustes → VPN y vuelve a conectar.",
  "Das VPN-Profil ist kaputt. Lösche „Volta“ unter Einstellungen → VPN und verbinde neu.")
T("vpn.error.failed", "VPN не подключился", "The VPN didn't connect", "VPN не підключився", "VPN қосылмады", "VPN ulanmadi",
  "VPN bağlanmadı", "VPN وصل نشد", "VPN 未能连接", "La VPN no se conectó", "Das VPN hat sich nicht verbunden")
T("vpn.error.stale", "Профиль VPN устарел, попробуйте ещё раз", "The VPN profile is outdated, try again",
  "Профіль VPN застарів, спробуйте ще раз", "VPN профилі ескірген, қайта көріңіз", "VPN profili eskirgan, qayta urinib ko‘ring",
  "VPN profili eski, tekrar deneyin", "نمایه VPN قدیمی است، دوباره امتحان کنید", "VPN 配置已过时，请再试一次",
  "El perfil VPN está desactualizado, inténtalo otra vez", "Das VPN-Profil ist veraltet, versuche es noch einmal")
T("vpn.error.permission", "iOS не дала создать VPN. Разрешите добавить конфигурацию VPN, когда iPhone спросит.",
  "iOS didn't let Volta create the VPN. Allow adding the VPN configuration when the iPhone asks.",
  "iOS не дала створити VPN. Дозвольте додати конфігурацію VPN, коли iPhone запитає.",
  "iOS VPN жасауға рұқсат бермеді. iPhone сұрағанда VPN конфигурациясын қосуға рұқсат етіңіз.",
  "iOS VPN yaratishga ruxsat bermadi. iPhone so‘raganda VPN konfiguratsiyasini qo‘shishga ruxsat bering.",
  "iOS VPN oluşturmaya izin vermedi. iPhone sorduğunda VPN yapılandırmasının eklenmesine izin verin.",
  "iOS اجازه ساخت VPN را نداد. وقتی iPhone پرسید، اجازه افزودن پیکربندی VPN را بدهید.",
  "iOS 未允许创建 VPN。当 iPhone 询问时，请允许添加 VPN 配置。",
  "iOS no permitió crear la VPN. Permite añadir la configuración VPN cuando el iPhone lo pida.",
  "iOS hat das VPN nicht erlaubt. Erlaube das Hinzufügen der VPN-Konfiguration, wenn das iPhone fragt.")
T("vpn.error.entitlement",
  "Эта копия Volta подписана без права на VPN. Так бывает с бесплатным Apple ID: VPN работает только в сборке, подписанной платным аккаунтом разработчика.",
  "This copy of Volta is signed without the VPN permission. That happens with a free Apple ID: the VPN only works in a build signed with a paid developer account.",
  "Цю копію Volta підписано без права на VPN. Так буває з безкоштовним Apple ID: VPN працює лише у збірці, підписаній платним акаунтом розробника.",
  "Volta-ның бұл көшірмесі VPN құқығынсыз қол қойылған. Тегін Apple ID-мен солай болады: VPN тек ақылы әзірлеуші аккаунтымен қол қойылған жинақта жұмыс істейді.",
  "Volta’ning bu nusxasi VPN huquqisiz imzolangan. Bepul Apple ID bilan shunday bo‘ladi: VPN faqat pullik dasturchi akkaunti bilan imzolangan yig‘mada ishlaydi.",
  "Volta’nın bu kopyası VPN izni olmadan imzalanmış. Ücretsiz Apple ID ile böyle olur: VPN yalnızca ücretli geliştirici hesabıyla imzalanmış sürümde çalışır.",
  "این نسخه Volta بدون مجوز VPN امضا شده است. با Apple ID رایگان همین‌طور است: VPN فقط در نسخه‌ای کار می‌کند که با حساب پولی توسعه‌دهنده امضا شده باشد.",
  "此 Volta 副本的签名不含 VPN 权限。使用免费 Apple ID 签名就会这样：只有用付费开发者账号签名的版本才能使用 VPN。",
  "Esta copia de Volta está firmada sin el permiso de VPN. Pasa con un Apple ID gratuito: la VPN solo funciona en una versión firmada con una cuenta de desarrollador de pago.",
  "Diese Volta-Kopie ist ohne VPN-Berechtigung signiert. Das passiert mit einer kostenlosen Apple-ID: Das VPN funktioniert nur in einem Build, der mit einem bezahlten Entwicklerkonto signiert ist.")
