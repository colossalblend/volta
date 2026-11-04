package com.ars3nb.volta.core.apps

/** Which way a ready-made set of apps is meant to go. */
enum class AppRoute {
    /** Russian services that refuse a foreign address or ask for extra checks: past the VPN. */
    DIRECT,

    /** Foreign services that do not work from Russia without the VPN. */
    VPN
}

/**
 * A ready-made group of apps for split tunnelling, matched by package name.
 *
 * A set never touches an app that is not on the phone: the screen counts and picks only the
 * packages Android reports as installed, so a name that was renamed or never shipped in a
 * given store simply drops out. Several names per app cover the store and site builds, the
 * business and investment apps of the same bank, and the Android TV versions.
 */
data class AppPreset(
    val id: String,
    val title: String,
    val route: AppRoute,
    val packages: List<String>
)

/**
 * The sets themselves. Russian names were checked against RuStore and the NSPK list of SBP
 * banks (qr.nspk.ru/proxyapp/c2bmembers.json); foreign ones against Google Play listings,
 * APKMirror and F-Droid. A name that could not be confirmed is left out rather than guessed.
 *
 * The phone apps here all have a launcher icon, so the Google Play build, which sees only
 * launcher apps (the `<queries>` block in the manifest), finds them as well as the GitHub
 * build does with QUERY_ALL_PACKAGES. The Android TV versions are for the GitHub build.
 */
object AppPresets {

    /** What Android accepts as an application ID: two or more dot-separated parts. */
    val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    val all: List<AppPreset> = listOf(
        // ---------------------------------------------------------------- past the VPN
        AppPreset(
            id = "banks",
            title = "Банки",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.sberbankmobile", "ru.sberbank_sbbol", "ru.sberbank.investor", // Сбер: Онлайн, Бизнес, Инвестиции
                "ru.sberbank.sberkids", // СберKids
                "com.idamob.tinkoff.android", "ru.tinkoff.investing", "ru.tinkoff.sme", // Т-Банк, Т-Инвестиции, Т-Бизнес
                "ru.tinkoff.bnpl", // Долями
                "ru.vtb24.mobilebanking.android", "ru.vtb.invest", "ru.vtb.smb", // ВТБ, Мои Инвестиции, ВТБ Бизнес
                "ru.alfabank.mobile.android", "ru.alfadirect.app", "ru.alfabank.oavdo.amc", // Альфа, Альфа-Инвестиции, Альфа-Бизнес
                "ru.gazprombank.android.mobilebank.app", "ru.gazprombank.mobile.gpbbusiness", // Газпромбанк, ГПБ Бизнес
                "ru.gazprombank.broker", // Газпромбанк Инвестиции
                "ru.raiffeisennews", // Райффайзен Онлайн
                "ru.letobank.Prometheus", // Почта Банк
                "ru.ozon.fintech.finance", "ru.ozon.fintech.sme", // Озон Банк, для бизнеса
                "ru.lewis.dbo", "ru.mts.money", "ru.mts.pay", // МТС Банк, МТС Деньги, МТС Pay
                "ru.sovcomcard.halva.v1", "ru.sovcombank.investor", "ru.diftechsvc", // Совкомбанк: Халва, Инвестиции, Бизнес
                "ru.rshb.dbo", "ru.rshb.dboul", // Россельхозбанк, Бизнес-Онлайн
                "com.openbank", // Открытие
                "logo.com.mbanking", "ru.psbank.invest", "ru.psbank.msb.dev.psb_appstore", // ПСБ, Инвестиции, Бизнес
                "ru.rosbank.android", // Росбанк
                "ru.mkb.mobile", // МКБ
                "com.yandex.bank", // Яндекс Банк (Яндекс Пэй)
                "ru.wildberries.bank", "wildberries.business", // WB Банк, для бизнеса
                "ru.bankuralsib.mb.android", // Уралсиб
                "ru.akbars.mobile", // Ак Барс
                "com.bank.domrf.v2", "com.bssys.roscapretail", // Банк ДОМ.РФ
                "ru.bspb", // Банк Санкт-Петербург
                "ru.simpls.brs2.mobbank", // Русский Стандарт
                "cz.bsc.rc", // Ренессанс Банк
                "ru.otpbank.mobile", // ОТП Банк
                "ru.homecredit.mycredit", // Хоум Банк
                "ru.zenitonline.android", "ru.zenit.android", // Зенит
                "ru.skbbank.ib", // Синара
                "cb.ibank", // УБРиР
                "ru.ftc.faktura.absolutbank", // Абсолют Банк
                "ru.centrinvest.mobilebanking2018", // Центр-инвест
                "ru.ftc.faktura.expobank", // Экспобанк
                "com.idamobile.android.LockoBank", // Локо-Банк
                "com.idamobile.android.crediteuropa", // Кредит Европа Банк
                "com.svoi.bank", // Свой Банк
                "com.bifit.rncbbeta", // РНКБ
                "ru.unicredit.android", // ЮниКредит
                "com.tochka.bank", "ru.zhuck.webapp", // Точка
                "modulbank.ru.app", // Модульбанк
                "ru.bcs.bcsbank", "ru.broker.my", // БКС Банк, БКС Мир инвестиций
                "ru.yoo.money", // ЮMoney
                "ru.nspk.mirpay", "ru.nspk.sbpay", // Mir Pay, СБПэй
                "com.moex.finuslugi" // Финуслуги
            )
        ),
        AppPreset(
            id = "gov",
            title = "Госуслуги",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.rostel", // Госуслуги
                "ru.gosuslugi.goskey", // Госключ
                "ru.gosuslugi.auto", // Госуслуги Авто
                "ru.sigma.gisgkh", // Госуслуги.Дом
                "ru.gosuslugi.culture", // Госуслуги.Культура
                "ru.gosuslugi.pos", // Госуслуги.Решаем вместе
                "ru.gosuslugi.school", // Госуслуги.Моя школа
                "ru.gosuslugi.migrant", // Госуслуги для иностранцев
                "ru.rtlabs.mobile.ebs.gosuslugi.android", // Госуслуги Биометрия
                "ru.fns.lkfl", // Налоги ФЛ
                "com.gnivts.selfemployed", // Мой налог
                "ru.gnivc.lkip", // Личный кабинет ИП
                "ru.crptech.mark", // Честный знак
                "ru.sitesoft.fssp", // ФССП
                "ru.trudvsem.mobile", // Работа России
                "ru.fanid", // Fan ID
                "ru.mos.app", // Моя Москва (mos.ru)
                "ru.altarix.mos.pgu", // Госуслуги Москвы
                "ru.mos.polls", // Активный гражданин
                "com.programmisty.emiasapp" // ЕМИАС.ИНФО
            )
        ),
        AppPreset(
            id = "shops",
            title = "Маркетплейсы",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.ozon.app.android", // Ozon
                "com.wildberries.ru", // Wildberries
                "ru.beru.android", // Яндекс Маркет
                "com.avito.android", // Авито
                "ru.megamarket.marketplace", // Мегамаркет
                "com.kazanexpress.ke_app", // Магнит Маркет
                "ru.aliexpress.buyer", "com.alibaba.aliexpresshd", // AliExpress Россия и мировой
                "com.allgoritm.youla", // Юла
                "ru.cian.main", // Циан
                "ru.auto.ara", // Авто.ру
                "com.lamoda.lite", // Lamoda
                "goldapple.ru.goldapple.customers", // Золотое Яблоко
                "ru.letu", // Л'Этуаль
                "ru.rivegauche.app", // Рив Гош
                "ru.dns.shop.android", // DNS
                "ru.filit.mvideo.b2c", // М.Видео
                "ru.mvm.eldo", // Эльдорадо
                "ru.sportmaster.app", // Спортмастер
                "ru.detmir.dmbonus", // Детский мир
                "ru.leroymerlin.mobile", // Лемана ПРО
                "com.notissimus.allinstruments.android", // ВсеИнструменты.ру
                "ru.bestprice.fixprice", // Fix Price
                "com.flowwow", // Flowwow
                "com.logistic.sdek", // СДЭК
                "com.octopod.russianpost.client.android" // Почта России
            )
        ),
        AppPreset(
            id = "food",
            title = "Еда и продукты",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.sbcs.store", // Самокат
                "ru.instamart", // Купер
                "ru.foodfox.client", // Яндекс Еда
                "com.yandex.lavka", // Яндекс Лавка
                "com.deliveryclub", // Маркет Деливери
                "ru.ozon.fresh", // Ozon fresh
                "ru.vkusvill", // ВкусВилл
                "ru.tander.magnit", // Магнит
                "ru.pyaterochka.app.browser", // Пятёрочка
                "ru.perekrestok.app", // Перекрёсток
                "club.chizhik", // Чижик
                "com.icemobile.lenta.prod", // Лента
                "ru.utk.dostavka", // Утконос
                "ru.myauchan.droid", // Ашан
                "com.ru.dixy", // Дикси
                "ru.vcr.verniy_all", // Верный
                "ru.reksoft.okey", // О'КЕЙ
                "ru.myspar", // SPAR
                "ru.globus.app", // Глобус
                "ru.napoleonit.kb", // Красное&Белое
                "ru.bristol.bristol_app", // Бристоль
                "ru.winelab", // Winelab
                "com.edadeal.android", // Едадил
                "ru.dodopizza.app", // Додо Пицца
                "com.apegroup.mcdonaldsrussia", // Вкусно — и точка
                "ru.kfc.kfc_delivery", // Rostic's
                "ru.burgerking", // Бургер Кинг
                "ru.farfor", // Фарфор
                "ru.chibbis" // Chibbis
            )
        ),
        AppPreset(
            id = "yandex",
            title = "Яндекс",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.yandex.searchplugin", "com.yandex.searchapp", "com.yandex.aliceapp", // Яндекс с Алисой, Алиса
                "com.yandex.browser", "com.yandex.browser.lite", // Яндекс Браузер, Лайт
                "ru.yandex.taxi", "ru.yandex.uber", // Яндекс Go, Uber Россия
                "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.metro", // Карты, Навигатор, Метро
                "ru.yandex.music", // Яндекс Музыка
                "ru.yandex.disk", "ru.yandex.mail", "ru.yandex.telemost", // Диск, Почта, Телемост
                "ru.yandex.weatherplugin", "ru.yandex.translate", // Погода, Переводчик
                "ru.yandex.androidkeyboard", // Яндекс Клавиатура
                "com.yandex.iot", // Дом с Алисой
                "ru.yandex.travel", "ru.yandex.rasp", // Путешествия, Расписания
                "com.yandex.mobile.drive", // Яндекс Драйв
                "ru.yandex.taximeter", // Яндекс Про
                "ru.yandex.mobile.afisha", // Афиша
                "com.yandex.mobile.realty", // Недвижимость
                "ru.plus.bookmate", // Яндекс Книги
                "com.yandex.shedevrus", // Шедеврум
                "ru.yandex.mobile.gasstations", // Яндекс Заправки
                "ru.yandex.key" // Яндекс Ключ
            )
        ),
        AppPreset(
            id = "vk",
            title = "VK и MAX",
            route = AppRoute.DIRECT,
            packages = listOf(
                "com.vkontakte.android", // ВКонтакте
                "com.vk.im", // VK Мессенджер
                "com.uma.musicvk", // VK Музыка
                "com.vk.vkvideo", // VK Видео
                "com.vk.clips", // VK Клипы
                "com.vk.calls", // VK Звонки
                "live.vkplay.app", // VK Play Live
                "ru.oneme.app", // MAX
                "ru.ok.android", // Одноклассники
                "ru.ok.messages", // ТамТам
                "ru.mail.mailapp", // Почта Mail
                "ru.mail.cloud", // Облако Mail
                "ru.zen.android", // Дзен
                "ru.vk.store" // RuStore
            )
        ),
        AppPreset(
            id = "media",
            title = "Кино и музыка",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.kinopoisk", "ru.kinopoisk.tv", // Кинопоиск
                "ru.ivi.client", // Иви
                "ru.more.play", "tv.okko.androidtv", // Okko
                "ru.rt.video.app.mobile", "ru.rt.video.app.tv", // Wink
                "ru.mts.mtstv", // KION
                "ru.start.androidmobile", // START
                "gpm.tnt_premier", "one.premier.rustoretv", // PREMIER
                "com.ctcmediagroup.videomore", // more.tv
                "ru.rutube.app", "ru.rutube.app.tv", "ru.rutube.RutubeKids", // Rutube, Rutube Детям
                "ru.ntv.client", // НТВ
                "tv.ntvplus.app", // НТВ-ПЛЮС
                "com.gsgroup.tricoloronline.mobile", // Триколор Кино и ТВ
                "ru.ritmmedia.yappy", // Yappy
                "com.zvooq.openplay", // Звук
                "ru.mts.music.android", // МТС Музыка
                "ru.litres.android", // Литрес
                "ru.mybook" // MyBook
            )
        ),
        AppPreset(
            id = "travel",
            title = "Карты и поездки",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.dublgis.dgismobile", "otello.dgis.ru", // 2ГИС, Отелло
                "ru.rzd.pass", // РЖД Пассажирам
                "ru.tutu.tutu_emp", "ru.tutu.etrains", // Туту, Туту Электрички
                "ru.aeroflot", // Аэрофлот
                "ru.s7.android", // S7
                "aero.pobeda.twa", // Победа
                "ru.aviasales", // Авиасейлс
                "travel.ozon.mobile", // Ozon Путешествия
                "ru.ostrovok.android", // Островок
                "ru.sutochno_redesign", // Суточно.ру
                "ru.mosmetro.metro", // Метро Москвы
                "ru.mosparking.appnew", // Парковки Москвы
                "com.taxsee.taxsee", // Такси Максим
                "youdrive.today", // Ситидрайв
                "com.punicapp.whoosh", // Whoosh
                "ru.urentbike.app", // Юрент
                "ru.russianhighways.mobile", // Автодор
                "com.gpn.azs", // АЗС Газпромнефть
                "ru.serebryakovas.lukoilmobileapp", // Лукойл
                "ru.tatneft.gasstations" // Татнефть
            )
        ),
        AppPreset(
            id = "health",
            title = "Здоровье",
            route = AppRoute.DIRECT,
            packages = listOf(
                "com.docdoc.docdoc", // СберЗдоровье
                "ru.medtochka", // МедТочка
                "ru.fdoctor.fdocmob", // Семейный доктор
                "com.invitro.app", // Инвитро
                "ru.apteka", // Аптека.ру
                "com.apteka.sklad", // Аптека Апрель
                "ru.uteka.app", // Ютека
                "ru.getpharma.eapteka", // еАптека
                "ru.zdravcity.app", // Здравсити
                "ru.neopharm.stolichki", // Столички
                "com.platfomni.vita", // Вита
                "ru.sogaz.app" // СОГАЗ
            )
        ),
        AppPreset(
            id = "carriers",
            title = "Операторы связи",
            route = AppRoute.DIRECT,
            packages = listOf(
                "ru.mts.mymts", // Мой МТС
                "ru.megafon.mlk", // МегаФон
                "ru.beeline.services", // Мой Билайн
                "ru.tele2.mytele2", // T2
                "ru.yota.android", // Yota
                "ru.tinkoff.mvno", // Т-Мобайл
                "ru.sber.telecom", // СберМобайл
                "com.dartit.RTcabinet" // Мой Ростелеком
            )
        ),

        // ---------------------------------------------------------- through the VPN
        AppPreset(
            id = "youtube",
            title = "YouTube",
            route = AppRoute.VPN,
            packages = listOf(
                "com.google.android.youtube", // YouTube
                "com.google.android.apps.youtube.music", // YouTube Music
                "com.google.android.apps.youtube.kids", // YouTube Kids
                "com.google.android.apps.youtube.creator", // YouTube Studio
                "com.google.android.youtube.tv", // YouTube для Android TV
                "app.revanced.android.youtube", "app.revanced.android.apps.youtube.music", // ReVanced
                "app.rvx.android.youtube", "app.rvx.android.apps.youtube.music", // ReVanced Extended
                "com.vanced.android.youtube", "com.vanced.android.apps.youtube.music", // Vanced
                "org.schabi.newpipe", // NewPipe
                "org.polymorphicshade.tubular", // Tubular
                "com.github.libretube", // LibreTube
                "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", // SmartTube
                "com.futo.platformplayer", "com.futo.platformplayer.playstore" // Grayjay
            )
        ),
        AppPreset(
            id = "messengers",
            title = "Мессенджеры",
            route = AppRoute.VPN,
            packages = listOf(
                "org.telegram.messenger", "org.telegram.messenger.web", // Telegram из Google Play и с сайта
                "org.telegram.messenger.beta", // Telegram Beta
                "org.thunderdog.challegram", // Telegram X
                "org.telegram.plus", // Plus Messenger
                "tw.nekomimi.nekogram", // Nekogram
                "uz.unnarsx.cherrygram", // Cherrygram
                "com.whatsapp", "com.whatsapp.w4b", // WhatsApp, WhatsApp Business
                "org.thoughtcrime.securesms", "im.molly.app", // Signal, Molly
                "com.viber.voip", // Viber
                "com.discord", // Discord
                "com.facebook.orca", "com.facebook.mlite", // Messenger, Messenger Lite
                "jp.naver.line.android", // LINE
                "im.vector.app", // Element
                "ch.threema.app", // Threema
                "network.loki.messenger", // Session
                "chat.simplex.app", // SimpleX Chat
                "com.wire" // Wire
            )
        ),
        AppPreset(
            id = "social",
            title = "Соцсети",
            route = AppRoute.VPN,
            packages = listOf(
                "com.instagram.android", "com.instagram.lite", // Instagram, Instagram Lite
                "com.instagram.barcelona", // Threads
                "com.facebook.katana", "com.facebook.lite", // Facebook, Facebook Lite
                "com.twitter.android", // X (Twitter)
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", // TikTok
                "com.zhiliaoapp.musically.go", // TikTok Lite
                "com.reddit.frontpage", // Reddit
                "com.pinterest", // Pinterest
                "com.snapchat.android", // Snapchat
                "com.tumblr", // Tumblr
                "xyz.blueskyweb.app", // Bluesky
                "org.joinmastodon.android", // Mastodon
                "com.quora.android", // Quora
                "com.medium.reader", // Medium
                "com.imgur.mobile" // Imgur
            )
        ),
        AppPreset(
            id = "ai",
            title = "Нейросети",
            route = AppRoute.VPN,
            packages = listOf(
                "com.openai.chatgpt", "com.openai.sora", // ChatGPT, Sora
                "com.anthropic.claude", // Claude
                "com.google.android.apps.bard", // Gemini
                "com.google.android.apps.labs.language.tailwind", // NotebookLM
                "ai.perplexity.app.android", // Perplexity
                "com.microsoft.copilot", // Copilot
                "ai.x.grok", // Grok
                "com.facebook.stella", // Meta AI
                "ai.mistral.chat", // Le Chat
                "ai.character.app", // Character.AI
                "com.poe.android", // Poe
                "com.suno.android" // Suno
            )
        ),
        AppPreset(
            id = "streaming",
            title = "Видео и музыка",
            route = AppRoute.VPN,
            packages = listOf(
                "com.netflix.mediaclient", "com.netflix.ninja", // Netflix, Netflix для Android TV
                "tv.twitch.android.app", // Twitch
                "com.kick.mobile", // Kick
                "com.disney.disneyplus", // Disney+
                "com.amazon.avod.thirdpartyclient", // Prime Video
                "com.crunchyroll.crunchyroid", // Crunchyroll
                "com.vimeo.android.videoapp", // Vimeo
                "com.patreon.android", // Patreon
                "com.spotify.music", "com.spotify.lite", "com.spotify.tv.android", // Spotify, Lite, для TV
                "com.soundcloud.android", // SoundCloud
                "deezer.android.app", // Deezer
                "com.aspiro.tidal", // TIDAL
                "com.apple.android.music", // Apple Music
                "au.com.shiftyjelly.pocketcasts" // Pocket Casts
            )
        ),
        AppPreset(
            id = "work",
            title = "Работа и учёба",
            route = AppRoute.VPN,
            packages = listOf(
                "com.linkedin.android", // LinkedIn
                "us.zoom.videomeetings", // Zoom
                "com.microsoft.teams", // Microsoft Teams
                "com.skype.raider", // Skype
                "com.Slack", // Slack
                "notion.id", // Notion
                "com.figma.mirror", // Figma
                "com.realtimeboard", // Miro
                "com.canva.editor", // Canva
                "com.github.android", // GitHub
                "com.grammarly.android.keyboard", // Grammarly
                "org.coursera.android", // Coursera
                "com.udemy.android", // Udemy
                "com.upwork.android.apps.main", "com.upwork.android.apps.client", // Upwork
                "com.behance.behance", // Behance
                "ch.protonmail.android", // Proton Mail
                "de.tutao.tutanota", // Tuta
                "org.jitsi.meet" // Jitsi Meet
            )
        ),
        AppPreset(
            id = "browsers",
            title = "Браузеры",
            route = AppRoute.VPN,
            packages = listOf(
                "com.android.chrome", "com.chrome.beta", // Chrome, Chrome Beta
                "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus", // Firefox, Beta, Focus
                "com.brave.browser", // Brave
                "com.opera.browser", "com.opera.mini.native", "com.opera.gx", // Opera, Opera Mini, Opera GX
                "com.microsoft.emmx", // Edge
                "com.sec.android.app.sbrowser", // Samsung Internet
                "com.duckduckgo.mobile.android", // DuckDuckGo
                "com.vivaldi.browser", // Vivaldi
                "org.torproject.torbrowser", // Tor Browser
                "com.kiwibrowser.browser" // Kiwi
            )
        )
    )

    /** The sets meant for one direction, in the order the screen shows them. */
    fun forRoute(route: AppRoute): List<AppPreset> = all.filter { it.route == route }
}
