# Texts only the iPhone has, beyond strings_table.py: the five languages that table lacks
# (Belarusian, Azerbaijani, Arabic, French, Brazilian Portuguese), and newer iOS-only keys
# in all fifteen. Plain words, as on Android. Placeholders: %@ text, %d number.
#
# X(key, be, az, ar, fr, pt-BR)                    adds the five languages to a table key
# N(key, ru, en, uk, be, kk, uz, az, tr, fa, ar, zh-Hans, es, de, fr, pt-BR)   a new key
# XP(key, be=(one, few, many, other), az=(one, other), ar=(zero, one, two, few, many, other),
#    fr=(one, other), pt=(one, other))             plural forms for a table plural

NEW_LANGS = ["be", "az", "ar", "fr", "pt-BR"]
ALL = ["ru", "en", "uk", "be", "kk", "uz", "az", "tr", "fa", "ar", "zh-Hans", "es", "de", "fr", "pt-BR"]
EXTRA = {}


def X(key, *texts):
    assert len(texts) == 5, key
    EXTRA.setdefault(key, {}).update(dict(zip(NEW_LANGS, texts)))


def N(key, *texts):
    assert len(texts) == 15, f"{key}: {len(texts)}"
    assert key not in EXTRA, key
    EXTRA[key] = dict(zip(ALL, texts))


def XP(key, be, az, ar, fr, pt):
    EXTRA[key] = {
        "be": dict(zip(["one", "few", "many", "other"], be)),
        "az": dict(zip(["one", "other"], az)),
        "ar": dict(zip(["zero", "one", "two", "few", "many", "other"], ar)),
        "fr": dict(zip(["one", "other"], fr)),
        "pt-BR": dict(zip(["one", "other"], pt)),
    }


# ------------------------------------------------------------------ table keys, five more languages
X("balancer.noAnswer", "Серверы групы не адказалі. Паспрабуйце пазней.", "Qrupun serverləri cavab vermədi. Sonra yenidən cəhd edin.",
  "لم يستجب أي خادم في المجموعة. حاول لاحقًا.", "Aucun serveur du groupe n’a répondu. Réessayez plus tard.",
  "Nenhum servidor do grupo respondeu. Tente mais tarde.")
X("balancer.picked", "Выбраны «%@» · %d мс", "Seçildi: «%@» · %d ms", "تم اختيار «%@» · %d مللي ثانية", "Choisi : « %@ » · %d ms",
  "Escolhido: “%@” · %d ms")
X("connect.error.noAnswer", "Серверы не адказалі", "Serverlər cavab vermədi", "لم تستجب الخوادم", "Les serveurs n’ont pas répondu",
  "Os servidores não responderam")
X("connect.error.noServer", "Выберыце сервер у спісе", "Siyahıdan server seçin", "اختر خادمًا من القائمة", "Choisissez un serveur dans la liste",
  "Escolha um servidor na lista")
X("detect.amnezia.text", "Пакуль не працуе ў Volta для iPhone", "Hələ iPhone üçün Volta-da işləmir", "لا يعمل بعد في Volta لـ iPhone",
  "Ne fonctionne pas encore dans Volta pour iPhone", "Ainda não funciona no Volta para iPhone")
X("detect.server", "Сервер %@", "%@ serveri", "خادم %@", "Serveur %@", "Servidor %@")
X("detect.sub.text", "Серверы будуць абнаўляцца самі", "Serverlər özləri yenilənəcək", "ستتحدث الخوادم تلقائيًا",
  "Les serveurs se mettront à jour tout seuls", "Os servidores se atualizam sozinhos")
X("editor.delete.text", "Сыдуць яе серверы. Астатнія падпіскі застануцца як ёсць.", "Onun serverləri silinəcək. Digər abunəliklər olduğu kimi qalacaq.",
  "ستُحذف خوادمه. تبقى الاشتراكات الأخرى كما هي.", "Ses serveurs partiront avec lui. Les autres abonnements restent tels quels.",
  "Os servidores dela saem junto. As outras assinaturas ficam como estão.")
X("editor.hiddenLink", "Сэрвіс схаваў спасылку: яе нельга паглядзець ці змяніць, але падпіска абнаўляецца як звычайна.",
  "Xidmət linki gizlədib: ona baxmaq və ya dəyişmək olmaz, amma abunəlik adi qaydada yenilənir.",
  "أخفت الخدمة الرابط: لا يمكن عرضه أو تغييره، لكن الاشتراك يتحدث كالمعتاد.",
  "Le service a masqué le lien : impossible de le voir ou de le modifier, mais l’abonnement se met à jour comme d’habitude.",
  "O serviço ocultou o link: não dá para ver nem alterar, mas a assinatura atualiza normalmente.")
X("editor.link", "Спасылка на падпіску", "Abunəlik linki", "رابط الاشتراك", "Lien de l’abonnement", "Link da assinatura")
X("editor.refresh.hint", "Спампаваць свежы спіс сервераў", "Serverlərin təzə siyahısını yüklə", "تنزيل قائمة خوادم جديدة",
  "Télécharger une liste de serveurs à jour", "Baixar uma lista nova de servidores")
X("editor.support", "Падтрымка сэрвісу", "Xidmətin dəstəyi", "دعم الخدمة", "Support du service", "Suporte do serviço")
X("import.already", "Гэтыя серверы ўжо ёсць у спісе", "Bu serverlər artıq siyahıdadır", "هذه الخوادم موجودة في القائمة بالفعل",
  "Ces serveurs sont déjà dans la liste", "Esses servidores já estão na lista")
X("import.error.amnezia", "Ключы AmneziaVPN пакуль не працуюць у Volta для iPhone", "AmneziaVPN açarları hələ iPhone üçün Volta-da işləmir",
  "مفاتيح AmneziaVPN لا تعمل بعد في Volta لـ iPhone", "Les clés AmneziaVPN ne fonctionnent pas encore dans Volta pour iPhone",
  "As chaves AmneziaVPN ainda não funcionam no Volta para iPhone")
X("import.error.nothing", "Сервераў не знойдзена. Праверце, што скапіявалі спасылку цалкам.",
  "Server tapılmadı. Linki tam kopyaladığınızı yoxlayın.", "لم يتم العثور على خوادم. تأكد من نسخ الرابط كاملًا.",
  "Aucun serveur trouvé. Vérifiez que vous avez copié le lien en entier.", "Nenhum servidor encontrado. Confira se copiou o link inteiro.")
X("import.error.unsupported", "Volta для iPhone пакуль не можа адкрыць гэтыя серверы: %@", "iPhone üçün Volta bu serverləri hələ aça bilmir: %@",
  "لا يستطيع Volta لـ iPhone فتح هذه الخوادم بعد: %@", "Volta pour iPhone ne sait pas encore ouvrir ces serveurs : %@",
  "O Volta para iPhone ainda não abre estes servidores: %@")
X("import.fileUnreadable", "Не атрымалася прачытаць файл", "Faylı oxumaq alınmadı", "تعذّرت قراءة الملف", "Impossible de lire le fichier",
  "Não foi possível ler o arquivo")
X("import.fmt.file.text", "Файл, які даслаў сэрвіс (.conf або .json): адкрыйце яго кнопкай вышэй",
  "Xidmətin göndərdiyi fayl (.conf və ya .json): yuxarıdakı düymə ilə açın",
  "ملف أرسلته الخدمة (.conf أو .json): افتحه بالزر أعلاه",
  "Un fichier envoyé par le service (.conf ou .json) : ouvrez-le avec le bouton ci-dessus",
  "Um arquivo que o serviço enviou (.conf ou .json): abra com o botão acima")
X("import.fmt.server.text", "Спасылка на адзін сервер: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Bir serverin linki: vless://, vmess://, trojan://, ss://, hysteria2://",
  "رابط لخادم واحد: vless://, vmess://, trojan://, ss://, hysteria2://",
  "Un lien vers un serveur : vless://, vmess://, trojan://, ss://, hysteria2://",
  "Um link para um servidor: vless://, vmess://, trojan://, ss://, hysteria2://")
X("import.placeholder", "Устаўце сюды спасылку на падпіску або на сервер (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Bura abunəlik və ya server linkini yapışdırın (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "الصق هنا رابط اشتراك أو رابط خادم (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Collez ici un lien d’abonnement ou de serveur (vless://, vmess://, trojan://, ss://, hysteria2://)",
  "Cole aqui um link de assinatura ou de servidor (vless://, vmess://, trojan://, ss://, hysteria2://)")
X("network.whileConnected", "Правераная перад падключэннем", "Qoşulmadan əvvəl yoxlanılıb", "تم الفحص قبل الاتصال",
  "Vérifié avant la connexion", "Verificado antes de conectar")
X("phase.measuring", "Шукаю хуткі сервер…", "Sürətli server axtarılır…", "جارٍ البحث عن خادم سريع…", "Recherche d’un serveur rapide…",
  "Procurando um servidor rápido…")
X("phase.probing", "Правяраю сетку…", "Şəbəkə yoxlanılır…", "جارٍ فحص الشبكة…", "Vérification du réseau…", "Verificando a rede…")
X("ping.whileConnected", "Пакуль VPN уключаны, час адказу можа быць крыху завышаны",
  "VPN açıq olanda cavab müddəti bir az çox görünə bilər", "أثناء تشغيل VPN قد يبدو زمن الاستجابة أعلى قليلًا",
  "Tant que le VPN est actif, le temps de réponse peut paraître un peu plus élevé",
  "Com a VPN ligada, o tempo de resposta pode parecer um pouco maior")
X("server.error.notInThisBuild", "%@ пакуль не працуе ў Volta для iPhone", "%@ hələ iPhone üçün Volta-da işləmir",
  "%@ لا يعمل بعد في Volta لـ iPhone", "%@ ne fonctionne pas encore dans Volta pour iPhone", "%@ ainda não funciona no Volta para iPhone")
X("servers.countWithCountries", "%@, краін: %d", "%@, ölkə: %d", "%@، الدول: %d", "%@, pays : %d", "%@, países: %d")
X("servers.from", "з %@", "%@ mənbəyindən", "من %@", "de %@", "de %@")
X("settings.applyOnReconnect", "Змены запрацуюць пры наступным падключэнні", "Dəyişikliklər növbəti qoşulmada işə düşəcək",
  "ستسري التغييرات عند الاتصال التالي", "Les changements s’appliquent à la prochaine connexion", "As mudanças valem na próxima conexão")
X("settings.core", "Ядро Xray %@", "Xray nüvəsi %@", "نواة Xray ‏%@", "Cœur Xray %@", "Núcleo Xray %@")
X("settings.engine", "Як перадаецца трафік", "Trafik necə ötürülür", "طريقة تمرير البيانات", "Comment le trafic est transmis",
  "Como o tráfego é repassado")
X("settings.engine.hint", "Калі VPN уключаецца, але сайты не адкрываюцца, паспрабуйце іншы спосаб.",
  "VPN qoşulur, amma saytlar açılmırsa, digər üsulu sınayın.", "إذا اشتغل VPN ولم تُفتح المواقع، جرّب الطريقة الأخرى.",
  "Si le VPN s’active mais que les sites ne s’ouvrent pas, essayez l’autre méthode.",
  "Se a VPN liga mas os sites não abrem, tente o outro modo.")
X("settings.engine.main", "Асноўны", "Əsas", "الأساسي", "Principal", "Principal")
X("settings.engine.spare", "Запасны", "Ehtiyat", "الاحتياطي", "De secours", "Reserva")
X("settings.help.hint", "Як дадаць падпіску і выбраць рэжым", "Abunəliyi necə əlavə etmək və rejim seçmək",
  "كيف تضيف اشتراكًا وتختار وضعًا", "Comment ajouter un abonnement et choisir un mode", "Como adicionar uma assinatura e escolher um modo")
X("settings.keep", "Трымаць VPN уключаным", "VPN-i açıq saxla", "إبقاء VPN مفعّلًا", "Garder le VPN actif", "Manter a VPN ligada")
X("settings.keep.hint", "iPhone сам уключыць VPN, калі сувязь абарвецца. Выключыць можна толькі ў Volta.",
  "Bağlantı kəsilsə, iPhone VPN-i özü yenidən açacaq. Söndürmək yalnız Volta-da olar.",
  "يعيد iPhone تشغيل VPN إذا انقطع الاتصال. يمكن إيقافه من Volta فقط.",
  "L’iPhone réactive le VPN si la connexion coupe. Vous ne pouvez l’arrêter que dans Volta.",
  "O iPhone religa a VPN se a conexão cair. Só dá para desligar no Volta.")
X("settings.language.hint", "Цяпер: %@. Адкрыюцца налады iPhone — выберыце там «Мова».",
  "İndi: %@. iPhone ayarları açılacaq — orada «Dil» seçin.", "الآن: %@. ستُفتح إعدادات iPhone — اختر «اللغة» هناك.",
  "Actuellement : %@. Les réglages de l’iPhone s’ouvrent : choisissez « Langue ».",
  "Agora: %@. Os ajustes do iPhone vão abrir: escolha “Idioma”.")
X("settings.language.row", "Мова праграмы", "Tətbiqin dili", "لغة التطبيق", "Langue de l’app", "Idioma do app")
X("settings.refreshOnLaunch.hint", "Серверы абнаўляюцца, калі вы адкрываеце праграму.", "Tətbiqi açanda serverlər yenilənir.",
  "تتحدث الخوادم عند فتح التطبيق.", "Les serveurs se mettent à jour à l’ouverture de l’app.", "Os servidores atualizam quando você abre o app.")
X("settings.russia.hint", "Сайты ў .ru, .su і .рф і вядомыя расійскія сэрвісы адкрываюцца без VPN.",
  ".ru, .su və .рф saytları və tanınmış Rusiya xidmətləri VPN-siz açılır.",
  "تُفتح مواقع .ru و.su و.рф والخدمات الروسية المعروفة دون VPN.",
  "Les sites en .ru, .su et .рф et les services russes connus s’ouvrent sans VPN.",
  "Sites em .ru, .su e .рф e serviços russos conhecidos abrem sem VPN.")
X("settings.signingNote", "Volta для iPhone не распаўсюджваецца праз App Store. VPN працуе толькі ў зборцы, падпісанай платным акаўнтам распрацоўшчыка Apple.",
  "iPhone üçün Volta App Store vasitəsilə yayılmır. VPN yalnız ödənişli Apple developer hesabı ilə imzalanmış quraşdırmada işləyir.",
  "لا يُوزّع Volta لـ iPhone عبر App Store. يعمل VPN فقط في نسخة موقّعة بحساب مطوّر Apple مدفوع.",
  "Volta pour iPhone n’est pas distribué sur l’App Store. Le VPN ne fonctionne que dans une version signée par un compte développeur Apple payant.",
  "O Volta para iPhone não é distribuído pela App Store. A VPN só funciona numa versão assinada com uma conta paga de desenvolvedor Apple.")
X("settings.source", "Зыходны код", "Mənbə kodu", "الشيفرة المصدرية", "Code source", "Código-fonte")
X("settings.version", "Volta %@", "Volta %@", "Volta %@", "Volta %@", "Volta %@")
X("settings.vpnProfile", "Налады iPhone", "iPhone ayarları", "إعدادات iPhone", "Réglages de l’iPhone", "Ajustes do iPhone")
X("settings.vpnProfile.hint", "Старонка Volta ў наладах: мова, апавяшчэнні і дазволы", "Ayarlarda Volta səhifəsi: dil, bildirişlər və icazələr",
  "صفحة Volta في الإعدادات: اللغة والإشعارات والأذونات", "La page de Volta dans Réglages : langue, notifications et autorisations",
  "A página do Volta nos Ajustes: idioma, notificações e permissões")
X("speed.total", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@", "↓ %@  ↑ %@")
X("sub.added", "Падпіска «%@» дададзена", "«%@» abunəliyi əlavə edildi", "تمت إضافة الاشتراك «%@»", "Abonnement « %@ » ajouté",
  "Assinatura “%@” adicionada")
X("sub.error.badLink", "Гэта не падобна на спасылку", "Bu linkə oxşamır", "لا يبدو هذا رابطًا", "Cela ne ressemble pas à un lien",
  "Isso não parece um link")
X("sub.error.onlyUnsupported", "У падпісцы толькі серверы, якія Volta для iPhone пакуль не адкрывае: %@",
  "Abunəlikdə yalnız iPhone üçün Volta-nın hələ aça bilmədiyi serverlər var: %@",
  "لا يحتوي الاشتراك إلا على خوادم لا يفتحها Volta لـ iPhone بعد: %@",
  "L’abonnement ne contient que des serveurs que Volta pour iPhone ne sait pas encore ouvrir : %@",
  "A assinatura só tem servidores que o Volta para iPhone ainda não abre: %@")
X("sub.error.status", "Сэрвіс не аддаў падпіску (памылка %d). Праверце спасылку ці тэрмін падпіскі.",
  "Xidmət abunəliyi vermədi (xəta %d). Linki və ya abunəlik müddətini yoxlayın.",
  "لم تُرجع الخدمة الاشتراك (خطأ %d). تحقق من الرابط أو مدة الاشتراك.",
  "Le service n’a pas renvoyé l’abonnement (erreur %d). Vérifiez le lien ou la durée de l’abonnement.",
  "O serviço não devolveu a assinatura (erro %d). Confira o link ou o prazo da assinatura.")
X("sub.refreshFailed", "Не абноўлена падпісак: %d", "Yenilənməyən abunəlik: %d", "اشتراكات لم تتحدث: %d", "Abonnements non mis à jour : %d",
  "Assinaturas não atualizadas: %d")
X("sub.refreshedAll", "Падпіскі абноўлены", "Abunəliklər yeniləndi", "تم تحديث الاشتراكات", "Abonnements mis à jour", "Assinaturas atualizadas")
X("tunnel.error.bridge", "Перадача трафіку спынілася (код %d)", "Trafik ötürülməsi dayandı (kod %d)", "توقف تمرير البيانات (الرمز %d)",
  "La transmission du trafic s’est arrêtée (code %d)", "O repasse do tráfego parou (código %d)")
X("tunnel.error.core", "Ядро Xray не запусцілася: %@", "Xray nüvəsi işə düşmədi: %@", "لم تبدأ نواة Xray: %@", "Le cœur Xray n’a pas démarré : %@",
  "O núcleo Xray não iniciou: %@")
X("tunnel.error.noInterface", "Не атрымалася падключыцца да VPN-інтэрфейсу iOS", "iOS VPN interfeysinə qoşulmaq alınmadı",
  "تعذّر الارتباط بواجهة VPN في iOS", "Impossible de se rattacher à l’interface VPN d’iOS", "Não foi possível ligar à interface VPN do iOS")
X("tunnel.error.noPlan", "Няма налад падключэння. Уключыце VPN з праграмы Volta.", "Qoşulma ayarları yoxdur. VPN-i Volta tətbiqindən açın.",
  "لا توجد إعدادات اتصال. شغّل VPN من تطبيق Volta.", "Aucun réglage de connexion. Activez le VPN depuis l’app Volta.",
  "Sem ajustes de conexão. Ligue a VPN pelo app Volta.")
X("tunnel.error.settings", "iOS не прыняла налады VPN: %@", "iOS VPN ayarlarını qəbul etmədi: %@", "لم يقبل iOS إعدادات VPN: %@",
  "iOS n’a pas accepté les réglages du VPN : %@", "O iOS não aceitou os ajustes da VPN: %@")
X("vpn.error.config", "Профіль VPN пашкоджаны. Выдаліце «Volta» ў Налады → VPN і падключыцеся зноў.",
  "VPN profili zədələnib. Ayarlar → VPN bölməsində «Volta»-nı silin və yenidən qoşulun.",
  "ملف VPN تالف. احذف «Volta» من الإعدادات ← VPN ثم اتصل مجددًا.",
  "Le profil VPN est abîmé. Supprimez « Volta » dans Réglages → VPN et reconnectez-vous.",
  "O perfil da VPN está danificado. Apague “Volta” em Ajustes → VPN e conecte de novo.")
X("vpn.error.entitlement", "Гэтая копія Volta падпісана без права на VPN. Так бывае з бясплатным Apple ID: VPN працуе толькі ў зборцы, падпісанай платным акаўнтам распрацоўшчыка.",
  "Volta-nın bu nüsxəsi VPN icazəsi olmadan imzalanıb. Pulsuz Apple ID ilə belə olur: VPN yalnız ödənişli developer hesabı ilə imzalanmış quraşdırmada işləyir.",
  "هذه النسخة من Volta موقّعة دون إذن VPN. يحدث هذا مع Apple ID مجاني: يعمل VPN فقط في نسخة موقّعة بحساب مطوّر مدفوع.",
  "Cette copie de Volta est signée sans le droit VPN. C’est le cas avec un identifiant Apple gratuit : le VPN ne fonctionne que dans une version signée par un compte développeur payant.",
  "Esta cópia do Volta foi assinada sem a permissão de VPN. Isso acontece com um Apple ID grátis: a VPN só funciona numa versão assinada com conta paga de desenvolvedor.")
X("vpn.error.failed", "VPN не падключыўся", "VPN qoşulmadı", "لم يتصل VPN", "Le VPN ne s’est pas connecté", "A VPN não conectou")
X("vpn.error.permission", "iOS не дазволіла Volta стварыць VPN. Дазвольце дадаць канфігурацыю VPN, калі iPhone спытае.",
  "iOS Volta-ya VPN yaratmağa icazə vermədi. iPhone soruşanda VPN konfiqurasiyasını əlavə etməyə icazə verin.",
  "لم يسمح iOS لـ Volta بإنشاء VPN. اسمح بإضافة إعداد VPN عندما يطلب iPhone ذلك.",
  "iOS n’a pas autorisé Volta à créer le VPN. Autorisez l’ajout de la configuration VPN quand l’iPhone le demande.",
  "O iOS não deixou o Volta criar a VPN. Permita adicionar a configuração de VPN quando o iPhone pedir.")
X("vpn.error.stale", "Профіль VPN састарэў, паспрабуйце яшчэ раз", "VPN profili köhnəlib, yenidən cəhd edin", "ملف VPN قديم، حاول مجددًا",
  "Le profil VPN est périmé, réessayez", "O perfil da VPN está desatualizado, tente de novo")

# ------------------------------------------------------------------ plurals
XP("servers.count",
   be=("%d сервер", "%d серверы", "%d сервераў", "%d сервера"), az=("%d server", "%d server"),
   ar=("لا خوادم", "خادم واحد", "خادمان", "%d خوادم", "%d خادمًا", "%d خادم"),
   fr=("%d serveur", "%d serveurs"), pt=("%d servidor", "%d servidores"))
XP("import.added",
   be=("Дададзены %d сервер", "Дададзена %d серверы", "Дададзена %d сервераў", "Дададзена %d сервера"),
   az=("%d server əlavə edildi", "%d server əlavə edildi"),
   ar=("لم تُضف خوادم", "أُضيف خادم واحد", "أُضيف خادمان", "أُضيفت %d خوادم", "أُضيف %d خادمًا", "أُضيف %d خادم"),
   fr=("%d serveur ajouté", "%d serveurs ajoutés"), pt=("%d servidor adicionado", "%d servidores adicionados"))
XP("expiry.daysLeft",
   be=("застаўся %d дзень", "засталося %d дні", "засталося %d дзён", "засталося %d дня"),
   az=("%d gün qalıb", "%d gün qalıb"),
   ar=("لم يبقَ أي يوم", "بقي يوم واحد", "بقي يومان", "بقيت %d أيام", "بقي %d يومًا", "بقي %d يوم"),
   fr=("%d jour restant", "%d jours restants"), pt=("falta %d dia", "faltam %d dias"))

# ------------------------------------------------------------------ new iOS-only keys, all fifteen
N("geo.error.tooBig",
  "Список %@ слишком большой для iPhone: VPN-расширению iOS даёт около 50 МБ памяти. Уберите его из правил или замените на сайты и адреса.",
  "The list %@ is too big for an iPhone: iOS gives the VPN extension about 50 MB of memory. Remove it from the rules or use sites and addresses instead.",
  "Список %@ завеликий для iPhone: iOS дає VPN-розширенню близько 50 МБ пам’яті. Приберіть його з правил або замініть сайтами й адресами.",
  "Спіс %@ занадта вялікі для iPhone: iOS дае VPN-пашырэнню каля 50 МБ памяці. Прыберыце яго з правіл або замяніце сайтамі і адрасамі.",
  "%@ тізімі iPhone үшін тым үлкен: iOS VPN кеңейтіміне шамамен 50 МБ жад береді. Оны ережелерден алып тастаңыз немесе сайттар мен мекенжайлармен ауыстырыңыз.",
  "%@ roʻyxati iPhone uchun juda katta: iOS VPN kengaytmasiga taxminan 50 MB xotira beradi. Uni qoidalardan olib tashlang yoki saytlar va manzillar bilan almashtiring.",
  "%@ siyahısı iPhone üçün çox böyükdür: iOS VPN genişlənməsinə təxminən 50 MB yaddaş verir. Onu qaydalardan çıxarın və ya sayt və ünvanlarla əvəz edin.",
  "%@ listesi iPhone için çok büyük: iOS, VPN uzantısına yaklaşık 50 MB bellek veriyor. Kurallardan çıkarın ya da yerine siteler ve adresler kullanın.",
  "فهرست %@ برای iPhone خیلی بزرگ است: iOS به افزونهٔ VPN حدود ۵۰ مگابایت حافظه می‌دهد. آن را از قوانین بردارید یا به‌جایش سایت و نشانی بگذارید.",
  "القائمة %@ كبيرة جدًا على iPhone: يمنح iOS ملحق VPN نحو 50 ميغابايت من الذاكرة. احذفها من القواعد أو استبدلها بمواقع وعناوين.",
  "列表 %@ 对 iPhone 来说太大：iOS 只给 VPN 扩展约 50 MB 内存。请把它从规则中移除，或改用网站和地址。",
  "La lista %@ es demasiado grande para un iPhone: iOS da a la extensión VPN unos 50 MB de memoria. Quítala de las reglas o usa sitios y direcciones.",
  "Die Liste %@ ist zu groß für ein iPhone: iOS gibt der VPN-Erweiterung etwa 50 MB Speicher. Entfernen Sie sie aus den Regeln oder nehmen Sie Seiten und Adressen.",
  "La liste %@ est trop grande pour un iPhone : iOS donne environ 50 Mo de mémoire à l’extension VPN. Retirez-la des règles ou utilisez des sites et des adresses.",
  "A lista %@ é grande demais para o iPhone: o iOS dá à extensão de VPN cerca de 50 MB de memória. Tire-a das regras ou use sites e endereços.")
N("guard.switched",
  "Сервер сменился сам: %@", "The server changed by itself: %@", "Сервер змінився сам: %@", "Сервер змяніўся сам: %@",
  "Сервер өзі ауысты: %@", "Server oʻzi almashdi: %@", "Server özü dəyişdi: %@", "Sunucu kendiliğinden değişti: %@",
  "سرور خودکار عوض شد: %@", "تغيّر الخادم تلقائيًا: %@", "服务器已自动切换：%@", "El servidor cambió solo: %@",
  "Der Server hat von selbst gewechselt: %@", "Le serveur a changé tout seul : %@", "O servidor mudou sozinho: %@")
N("language.restart",
  "Язык сменится, когда Volta откроется заново", "The language changes the next time Volta opens",
  "Мова зміниться, коли Volta відкриється знову", "Мова зменіцца, калі Volta адкрыецца зноў",
  "Тіл Volta қайта ашылғанда ауысады", "Til Volta qayta ochilganda almashadi", "Dil Volta yenidən açılanda dəyişəcək",
  "Dil, Volta yeniden açıldığında değişir", "زبان در بازشدن بعدی Volta عوض می‌شود", "ستتغير اللغة عند فتح Volta مرة أخرى",
  "下次打开 Volta 时语言会更改", "El idioma cambiará la próxima vez que abras Volta", "Die Sprache wechselt beim nächsten Öffnen von Volta",
  "La langue changera à la prochaine ouverture de Volta", "O idioma muda na próxima vez que o Volta abrir")
N("routing.export.copied",
  "Правила скопированы", "Rules copied", "Правила скопійовано", "Правілы скапіяваны", "Ережелер көшірілді", "Qoidalar nusxalandi",
  "Qaydalar kopyalandı", "Kurallar kopyalandı", "قوانین کپی شد", "تم نسخ القواعد", "规则已复制", "Reglas copiadas", "Regeln kopiert",
  "Règles copiées", "Regras copiadas")
N("routing.export.hint",
  "Скопировать правила набора в JSON Xray", "Copy the set's rules as Xray JSON", "Скопіювати правила набору в JSON Xray",
  "Скапіяваць правілы набору ў JSON Xray", "Жинақ ережелерін Xray JSON түрінде көшіру", "Toʻplam qoidalarini Xray JSON sifatida nusxalash",
  "Dəstin qaydalarını Xray JSON kimi kopyala", "Setin kurallarını Xray JSON olarak kopyala", "کپی قوانین این مجموعه به‌صورت JSON Xray",
  "نسخ قواعد المجموعة بصيغة Xray JSON", "以 Xray JSON 复制此规则集", "Copiar las reglas del conjunto como JSON de Xray",
  "Regeln des Satzes als Xray-JSON kopieren", "Copier les règles de l’ensemble en JSON Xray", "Copiar as regras do conjunto como JSON do Xray")
N("routing.import.title",
  "Вставить правила", "Paste rules", "Вставити правила", "Уставіць правілы", "Ережелерді қою", "Qoidalarni qoʻyish", "Qaydaları yapışdır",
  "Kuralları yapıştır", "چسباندن قوانین", "لصق القواعد", "粘贴规则", "Pegar reglas", "Regeln einfügen", "Coller des règles", "Colar regras")
N("routing.import.hint",
  "Из буфера обмена: массив правил или routing.rules", "From the clipboard: a list of rules or routing.rules",
  "З буфера обміну: масив правил або routing.rules", "З буфера абмену: масіў правіл або routing.rules",
  "Алмасу буферінен: ережелер тізімі немесе routing.rules", "Buferdan: qoidalar roʻyxati yoki routing.rules",
  "Mübadilə buferindən: qaydalar siyahısı və ya routing.rules", "Panodan: kural listesi veya routing.rules",
  "از کلیپ‌بورد: فهرست قوانین یا routing.rules", "من الحافظة: قائمة قواعد أو routing.rules", "从剪贴板：规则数组或 routing.rules",
  "Del portapapeles: una lista de reglas o routing.rules", "Aus der Zwischenablage: eine Regelliste oder routing.rules",
  "Depuis le presse-papiers : une liste de règles ou routing.rules", "Da área de transferência: uma lista de regras ou routing.rules")
N("server.error.helperOnly",
  "%@ не работает на iPhone: на Android его ведёт отдельная программа-помощник, а VPN-расширению iOS даёт около 50 МБ памяти и место только для ядра Xray.",
  "%@ doesn't work on an iPhone: on Android a separate helper program runs it, and iOS gives the VPN extension about 50 MB and room for the Xray core only.",
  "%@ не працює на iPhone: на Android його веде окрема програма-помічник, а VPN-розширенню iOS дає близько 50 МБ пам’яті й місце лише для ядра Xray.",
  "%@ не працуе на iPhone: на Android яго вядзе асобная праграма-памочнік, а VPN-пашырэнню iOS дае каля 50 МБ памяці і месца толькі для ядра Xray.",
  "%@ iPhone-да жұмыс істемейді: Android-та оны бөлек көмекші бағдарлама жүргізеді, ал iOS VPN кеңейтіміне шамамен 50 МБ және тек Xray ядросына орын береді.",
  "%@ iPhone’da ishlamaydi: Android’da uni alohida yordamchi dastur yuritadi, iOS esa VPN kengaytmasiga taxminan 50 MB va faqat Xray yadrosi uchun joy beradi.",
  "%@ iPhone-da işləmir: Android-də onu ayrıca köməkçi proqram idarə edir, iOS isə VPN genişlənməsinə təxminən 50 MB və yalnız Xray nüvəsi üçün yer verir.",
  "%@ iPhone’da çalışmaz: Android’de onu ayrı bir yardımcı program yürütür, iOS ise VPN uzantısına yaklaşık 50 MB ve yalnızca Xray çekirdeğine yer verir.",
  "%@ روی iPhone کار نمی‌کند: در Android برنامهٔ کمکی جداگانه‌ای آن را اجرا می‌کند و iOS به افزونهٔ VPN حدود ۵۰ مگابایت و جا فقط برای هستهٔ Xray می‌دهد.",
  "%@ لا يعمل على iPhone: في Android يشغّله برنامج مساعد منفصل، ويمنح iOS ملحق VPN نحو 50 ميغابايت ومكانًا لنواة Xray فقط.",
  "%@ 无法在 iPhone 上使用：在 Android 上由单独的辅助程序运行，而 iOS 只给 VPN 扩展约 50 MB 内存，只够放 Xray 内核。",
  "%@ no funciona en iPhone: en Android lo ejecuta un programa auxiliar aparte, y iOS da a la extensión VPN unos 50 MB y sitio solo para el núcleo Xray.",
  "%@ funktioniert auf dem iPhone nicht: Unter Android läuft es in einem eigenen Hilfsprogramm, und iOS gibt der VPN-Erweiterung etwa 50 MB und nur Platz für den Xray-Kern.",
  "%@ ne fonctionne pas sur iPhone : sous Android, un programme d’aide séparé le fait tourner, et iOS ne donne à l’extension VPN qu’environ 50 Mo et de la place pour le seul cœur Xray.",
  "%@ não funciona no iPhone: no Android um programa auxiliar separado o executa, e o iOS dá à extensão de VPN cerca de 50 MB e espaço só para o núcleo Xray.")
N("settings.iosLimits",
  "Чего нет на iPhone: AmneziaWG, Hysteria v1 и OpenFlux (им нужны программы-помощники, а расширению VPN iOS даёт около 50 МБ), выбор приложений для VPN (только на управляемых устройствах, вместо него — «Сайты мимо VPN» в «Маршрутах»), обновление самого приложения и передача по Bluetooth.",
  "What an iPhone doesn't have: AmneziaWG, Hysteria v1 and OpenFlux (they need helper programs, and iOS gives the VPN extension about 50 MB), choosing apps for the VPN (managed devices only; use «Sites past the VPN» in Routing instead), updating the app itself, and Bluetooth sharing.",
  "Чого немає на iPhone: AmneziaWG, Hysteria v1 і OpenFlux (їм потрібні програми-помічники, а розширенню VPN iOS дає близько 50 МБ), вибір застосунків для VPN (лише на керованих пристроях, замість нього — «Сайти повз VPN» у «Маршрутах»), оновлення самого застосунку й передавання через Bluetooth.",
  "Чаго няма на iPhone: AmneziaWG, Hysteria v1 і OpenFlux (ім патрэбныя праграмы-памочнікі, а пашырэнню VPN iOS дае каля 50 МБ), выбар праграм для VPN (толькі на кіраваных прыладах, замест яго — «Сайты міма VPN» у «Маршрутах»), абнаўленне самой праграмы і перадача праз Bluetooth.",
  "iPhone-да жоқ нәрселер: AmneziaWG, Hysteria v1 және OpenFlux (оларға көмекші бағдарламалар керек, ал iOS VPN кеңейтіміне шамамен 50 МБ береді), VPN үшін қолданбаларды таңдау (тек басқарылатын құрылғыларда; орнына «Маршруттардағы» «VPN-сіз сайттар»), қолданбаның өзін жаңарту және Bluetooth арқылы беру.",
  "iPhone’da yoʻq narsalar: AmneziaWG, Hysteria v1 va OpenFlux (ularga yordamchi dasturlar kerak, iOS esa VPN kengaytmasiga taxminan 50 MB beradi), VPN uchun ilovalarni tanlash (faqat boshqariladigan qurilmalarda; oʻrniga «Marshrutlar»dagi «VPN’siz saytlar»), ilovaning oʻzini yangilash va Bluetooth orqali ulashish.",
  "iPhone-da olmayanlar: AmneziaWG, Hysteria v1 və OpenFlux (onlara köməkçi proqramlar lazımdır, iOS isə VPN genişlənməsinə təxminən 50 MB verir), VPN üçün tətbiq seçimi (yalnız idarə olunan cihazlarda; əvəzində «Marşrutlar»da «VPN-siz saytlar»), tətbiqin özünü yeniləmək və Bluetooth ilə paylaşmaq.",
  "iPhone’da olmayanlar: AmneziaWG, Hysteria v1 ve OpenFlux (yardımcı programlar gerekir, iOS ise VPN uzantısına yaklaşık 50 MB veriyor), VPN için uygulama seçimi (yalnızca yönetilen cihazlarda; yerine Rotalar’daki «VPN dışı siteler»), uygulamanın kendini güncellemesi ve Bluetooth ile paylaşım.",
  "آنچه در iPhone نیست: AmneziaWG، Hysteria v1 و OpenFlux (برنامهٔ کمکی لازم دارند و iOS به افزونهٔ VPN حدود ۵۰ مگابایت می‌دهد)، انتخاب برنامه‌ها برای VPN (فقط در دستگاه‌های مدیریت‌شده؛ به‌جایش «سایت‌های بیرون از VPN» در «مسیرها»)، به‌روزرسانی خود برنامه و اشتراک با Bluetooth.",
  "ما لا يتوفر على iPhone: ‏AmneziaWG وHysteria v1 وOpenFlux (تحتاج برامج مساعدة، ويمنح iOS ملحق VPN نحو 50 ميغابايت)، اختيار التطبيقات لـ VPN (على الأجهزة المُدارة فقط؛ استخدم «مواقع خارج VPN» في «المسارات» بدلًا منه)، تحديث التطبيق نفسه، والمشاركة عبر Bluetooth.",
  "iPhone 上没有的功能：AmneziaWG、Hysteria v1 和 OpenFlux（需要辅助程序，而 iOS 只给 VPN 扩展约 50 MB），按应用选择 VPN（仅限受管理设备；可改用“路由”中的“绕过 VPN 的网站”），应用自行更新，以及蓝牙分享。",
  "Lo que no hay en iPhone: AmneziaWG, Hysteria v1 y OpenFlux (necesitan programas auxiliares, y iOS da a la extensión VPN unos 50 MB), elegir apps para la VPN (solo en dispositivos gestionados; usa «Sitios fuera de la VPN» en Rutas), que la app se actualice sola y compartir por Bluetooth.",
  "Was es auf dem iPhone nicht gibt: AmneziaWG, Hysteria v1 und OpenFlux (sie brauchen Hilfsprogramme, und iOS gibt der VPN-Erweiterung etwa 50 MB), Apps für das VPN auswählen (nur auf verwalteten Geräten; stattdessen „Seiten am VPN vorbei“ unter Routen), dass sich die App selbst aktualisiert, und Teilen per Bluetooth.",
  "Ce qui manque sur iPhone : AmneziaWG, Hysteria v1 et OpenFlux (il leur faut des programmes d’aide, et iOS donne environ 50 Mo à l’extension VPN), le choix des apps pour le VPN (appareils gérés seulement ; utilisez « Sites hors VPN » dans Itinéraires), la mise à jour de l’app par elle-même et le partage Bluetooth.",
  "O que não existe no iPhone: AmneziaWG, Hysteria v1 e OpenFlux (precisam de programas auxiliares, e o iOS dá à extensão de VPN cerca de 50 MB), escolher apps para a VPN (só em aparelhos gerenciados; use “Sites fora da VPN” em Rotas), o app se atualizar sozinho e compartilhar por Bluetooth.")
N("settings.language.now",
  "Сейчас: %@", "Now: %@", "Зараз: %@", "Цяпер: %@", "Қазір: %@", "Hozir: %@", "İndi: %@", "Şu an: %@", "اکنون: %@", "الآن: %@",
  "当前：%@", "Ahora: %@", "Jetzt: %@", "Actuellement : %@", "Agora: %@")
N("settings.routing.hint",
  "Российские сайты напрямую, свои сайты, блокировка рекламы и торрентов", "Russian sites direct, your own sites, ad and torrent blocking",
  "Російські сайти напряму, свої сайти, блокування реклами й торентів", "Расійскія сайты напрамую, свае сайты, блакіроўка рэкламы і торэнтаў",
  "Ресей сайттары тікелей, өз сайттарыңыз, жарнама мен торренттерді бұғаттау", "Rossiya saytlari toʻgʻridan-toʻgʻri, oʻz saytlaringiz, reklama va torrentlarni bloklash",
  "Rusiya saytları birbaşa, öz saytlarınız, reklam və torrent bloklanması", "Rus siteleri doğrudan, kendi siteleriniz, reklam ve torrent engelleme",
  "سایت‌های روسی مستقیم، سایت‌های خودتان، مسدودسازی تبلیغات و تورنت", "المواقع الروسية مباشرة، مواقعك الخاصة، حظر الإعلانات والتورنت",
  "俄罗斯网站直连、自定义网站、屏蔽广告和种子", "Sitios rusos directos, tus propios sitios, bloqueo de anuncios y torrents",
  "Russische Seiten direkt, eigene Seiten, Werbe- und Torrent-Sperre", "Sites russes en direct, vos propres sites, blocage des pubs et des torrents",
  "Sites russos direto, seus próprios sites, bloqueio de anúncios e torrents")
N("settings.updates",
  "Обновления", "Updates", "Оновлення", "Абнаўленні", "Жаңартулар", "Yangilanishlar", "Yeniləmələr", "Güncellemeler", "به‌روزرسانی‌ها",
  "التحديثات", "更新", "Actualizaciones", "Updates", "Mises à jour", "Atualizações")
N("settings.update.auto.on",
  "При запуске. О новой версии скажет эта строка", "On launch. This line tells you about a new version",
  "Під час запуску. Про нову версію скаже цей рядок", "Пры запуску. Пра новую версію скажа гэты радок",
  "Іске қосқанда. Жаңа нұсқа туралы осы жол айтады", "Ishga tushirishda. Yangi versiya haqida shu qator aytadi",
  "Açılışda. Yeni versiya barədə bu sətir deyəcək", "Açılışta. Yeni sürümü bu satır söyler", "هنگام باز شدن. این سطر از نسخهٔ تازه خبر می‌دهد",
  "عند التشغيل. يخبرك هذا السطر بالإصدار الجديد", "启动时检查。新版本会显示在这一行", "Al abrir. Esta línea te avisa de una versión nueva",
  "Beim Start. Diese Zeile zeigt eine neue Version an", "Au lancement. Cette ligne signale une nouvelle version",
  "Ao abrir. Esta linha avisa de uma versão nova")
N("settings.update.current",
  "У вас версия %@ (сборка %@)", "You have version %@ (build %@)", "У вас версія %@ (збірка %@)", "У вас версія %@ (зборка %@)",
  "Сізде %@ нұсқасы (жинақ %@)", "Sizda %@ versiya (yigʻma %@)", "Sizdə %@ versiyası var (yığım %@)", "Sürümünüz %@ (derleme %@)",
  "نسخهٔ شما %@ (ساخت %@)", "لديك الإصدار %@ (البناء %@)", "当前版本 %@（构建 %@）", "Tienes la versión %@ (compilación %@)",
  "Sie haben Version %@ (Build %@)", "Vous avez la version %@ (build %@)", "Você tem a versão %@ (build %@)")
N("settings.update.available.hint",
  "Откройте релиз и скачайте файл .ipa", "Open the release and download the .ipa file", "Відкрийте реліз і завантажте файл .ipa",
  "Адкрыйце рэліз і спампуйце файл .ipa", "Релизді ашып, .ipa файлын жүктеңіз", "Relizni oching va .ipa faylini yuklab oling",
  "Relizi açın və .ipa faylını yükləyin", "Sürümü açın ve .ipa dosyasını indirin", "انتشار را باز کنید و فایل .ipa را بگیرید",
  "افتح الإصدار ونزّل ملف ‎.ipa", "打开发布页并下载 .ipa 文件", "Abre la versión y descarga el archivo .ipa",
  "Öffnen Sie die Version und laden Sie die .ipa-Datei", "Ouvrez la version et téléchargez le fichier .ipa",
  "Abra a versão e baixe o arquivo .ipa")
N("settings.update.open",
  "Открыть", "Open", "Відкрити", "Адкрыць", "Ашу", "Ochish", "Aç", "Aç", "باز کردن", "فتح", "打开", "Abrir", "Öffnen", "Ouvrir", "Abrir")
N("settings.update.howto",
  "iPhone не даёт приложениям обновлять себя. Новую версию ставят тем же, чем подписана эта: AltStore или Sideloadly (открыть в них скачанный .ipa), TestFlight — само, TrollStore — открыть .ipa в нём. Подписки и настройки сохранятся.",
  "An iPhone doesn't let apps update themselves. Install the new version with whatever signed this one: AltStore or Sideloadly (open the downloaded .ipa in them), TestFlight does it by itself, TrollStore opens the .ipa. Subscriptions and settings stay.",
  "iPhone не дає застосункам оновлювати себе. Нову версію ставлять тим, чим підписано цю: AltStore чи Sideloadly (відкрити в них завантажений .ipa), TestFlight — сам, TrollStore — відкрити .ipa в ньому. Підписки й налаштування збережуться.",
  "iPhone не дае праграмам абнаўляць сябе. Новую версію ставяць тым, чым падпісана гэтая: AltStore ці Sideloadly (адкрыць у іх спампаваны .ipa), TestFlight — сам, TrollStore — адкрыць .ipa ў ім. Падпіскі і налады захаваюцца.",
  "iPhone қолданбаларға өздерін жаңартуға рұқсат бермейді. Жаңа нұсқаны осы нұсқа қол қойылған құралмен орнатыңыз: AltStore немесе Sideloadly (жүктелген .ipa-ны соларда ашыңыз), TestFlight өзі жаңартады, TrollStore — .ipa-ны онда ашыңыз. Жазылымдар мен баптаулар сақталады.",
  "iPhone ilovalarga oʻzini yangilashga ruxsat bermaydi. Yangi versiyani shu versiya imzolangan vosita bilan oʻrnating: AltStore yoki Sideloadly (yuklangan .ipa’ni ularda oching), TestFlight oʻzi yangilaydi, TrollStore — .ipa’ni unda oching. Obunalar va sozlamalar saqlanadi.",
  "iPhone tətbiqlərə özlərini yeniləməyə imkan vermir. Yeni versiyanı bunu imzalayan vasitə ilə quraşdırın: AltStore və ya Sideloadly (yüklənmiş .ipa-nı onlarda açın), TestFlight özü edir, TrollStore — .ipa-nı onda açın. Abunəliklər və ayarlar qalacaq.",
  "iPhone uygulamaların kendini güncellemesine izin vermez. Yeni sürümü bunu imzalayan araçla kurun: AltStore veya Sideloadly (indirilen .ipa’yı onlarda açın), TestFlight kendisi yapar, TrollStore’da .ipa’yı açın. Abonelikler ve ayarlar kalır.",
  "iPhone اجازه نمی‌دهد برنامه‌ها خودشان را به‌روز کنند. نسخهٔ تازه را با همان ابزاری نصب کنید که این نسخه را امضا کرده: AltStore یا Sideloadly (فایل .ipa دانلودشده را در آن‌ها باز کنید)، TestFlight خودش انجام می‌دهد، TrollStore — فایل .ipa را در آن باز کنید. اشتراک‌ها و تنظیمات می‌مانند.",
  "لا يسمح iPhone للتطبيقات بتحديث نفسها. ثبّت الإصدار الجديد بالأداة التي وقّعت هذا الإصدار: AltStore أو Sideloadly (افتح ملف ‎.ipa المنزّل فيهما)، ويقوم TestFlight بذلك بنفسه، وفي TrollStore افتح ملف ‎.ipa. تبقى الاشتراكات والإعدادات.",
  "iPhone 不允许应用自行更新。请用签名此版本的同一工具安装新版本：AltStore 或 Sideloadly（在其中打开下载的 .ipa），TestFlight 会自动更新，TrollStore 中打开 .ipa 即可。订阅和设置会保留。",
  "El iPhone no deja que las apps se actualicen solas. Instala la versión nueva con lo mismo que firmó esta: AltStore o Sideloadly (abre en ellos el .ipa descargado), TestFlight lo hace solo, en TrollStore abre el .ipa. Las suscripciones y los ajustes se conservan.",
  "Ein iPhone lässt Apps sich nicht selbst aktualisieren. Installieren Sie die neue Version mit dem, was diese signiert hat: AltStore oder Sideloadly (die geladene .ipa darin öffnen), TestFlight macht es selbst, in TrollStore die .ipa öffnen. Abos und Einstellungen bleiben.",
  "L’iPhone ne laisse pas les apps se mettre à jour toutes seules. Installez la nouvelle version avec ce qui a signé celle-ci : AltStore ou Sideloadly (ouvrez-y le .ipa téléchargé), TestFlight le fait tout seul, dans TrollStore ouvrez le .ipa. Abonnements et réglages sont conservés.",
  "O iPhone não deixa os apps se atualizarem sozinhos. Instale a versão nova com o mesmo que assinou esta: AltStore ou Sideloadly (abra neles o .ipa baixado), o TestFlight faz sozinho, no TrollStore abra o .ipa. Assinaturas e ajustes continuam.")
N("split.ios.title",
  "Сайты мимо VPN", "Sites past the VPN", "Сайти повз VPN", "Сайты міма VPN", "VPN-сіз сайттар", "VPN’siz saytlar", "VPN-siz saytlar",
  "VPN dışı siteler", "سایت‌های بیرون از VPN", "مواقع خارج VPN", "绕过 VPN 的网站", "Sitios fuera de la VPN", "Seiten am VPN vorbei",
  "Sites hors VPN", "Sites fora da VPN")
N("split.ios.text",
  "Выбирать приложения для VPN iPhone разрешает только на управляемых устройствах. Зато сайты банков, Госуслуг и других сервисов можно пустить напрямую: добавьте их сюда, и они пойдут мимо VPN.",
  "An iPhone allows choosing apps for the VPN only on managed devices. But the sites of banks, Gosuslugi and other services can go direct: add them here and they bypass the VPN.",
  "Вибирати застосунки для VPN iPhone дозволяє лише на керованих пристроях. Зате сайти банків, держпослуг та інших сервісів можна пустити напряму: додайте їх сюди, і вони підуть повз VPN.",
  "Выбіраць праграмы для VPN iPhone дазваляе толькі на кіраваных прыладах. Затое сайты банкаў, дзяржпаслуг і іншых сэрвісаў можна пусціць напрамую: дадайце іх сюды, і яны пойдуць міма VPN.",
  "iPhone VPN үшін қолданбаларды тек басқарылатын құрылғыларда таңдауға рұқсат етеді. Есесіне банктер, мемлекеттік қызметтер және басқа сервистер сайттарын тікелей жіберуге болады: оларды осында қосыңыз, олар VPN-сіз өтеді.",
  "iPhone VPN uchun ilovalarni faqat boshqariladigan qurilmalarda tanlashga ruxsat beradi. Lekin banklar, davlat xizmatlari va boshqa servislar saytlarini toʻgʻridan-toʻgʻri yuborish mumkin: ularni shu yerga qoʻshing, ular VPN’dan tashqarida oʻtadi.",
  "iPhone VPN üçün tətbiq seçməyə yalnız idarə olunan cihazlarda icazə verir. Amma bankların, dövlət xidmətlərinin və başqa xidmətlərin saytlarını birbaşa buraxmaq olar: onları bura əlavə edin, VPN-dən kənar keçəcəklər.",
  "iPhone, VPN için uygulama seçmeye yalnızca yönetilen cihazlarda izin verir. Ama bankaların, devlet hizmetlerinin ve diğer servislerin sitelerini doğrudan gönderebilirsiniz: buraya ekleyin, VPN dışından gitsinler.",
  "iPhone انتخاب برنامه‌ها برای VPN را فقط در دستگاه‌های مدیریت‌شده اجازه می‌دهد. اما سایت بانک‌ها، خدمات دولتی و سرویس‌های دیگر را می‌شود مستقیم فرستاد: آن‌ها را اینجا اضافه کنید تا بیرون از VPN بروند.",
  "لا يسمح iPhone باختيار التطبيقات لـ VPN إلا على الأجهزة المُدارة. لكن يمكن تمرير مواقع البنوك والخدمات الحكومية وغيرها مباشرة: أضفها هنا وستمر خارج VPN.",
  "iPhone 只允许在受管理设备上为 VPN 选择应用。但银行、政务等服务的网站可以直连：把它们添加到这里，就会绕过 VPN。",
  "El iPhone solo permite elegir apps para la VPN en dispositivos gestionados. Pero los sitios de bancos, trámites y otros servicios pueden ir directos: añádelos aquí y no pasarán por la VPN.",
  "Ein iPhone erlaubt die App-Auswahl für das VPN nur auf verwalteten Geräten. Die Seiten von Banken, Behörden und anderen Diensten können aber direkt gehen: Fügen Sie sie hier hinzu, dann laufen sie am VPN vorbei.",
  "L’iPhone ne permet de choisir les apps du VPN que sur les appareils gérés. Mais les sites des banques, des services publics et d’autres services peuvent passer en direct : ajoutez-les ici et ils contourneront le VPN.",
  "O iPhone só deixa escolher apps para a VPN em aparelhos gerenciados. Mas os sites de bancos, serviços públicos e outros podem ir direto: adicione-os aqui e eles passam fora da VPN.")
N("split.ios.where",
  "Добавляются в правило «Свои сайты напрямую» набора «%@»", "Added to the «Your sites direct» rule of the set «%@»",
  "Додаються до правила «Свої сайти напряму» набору «%@»", "Дадаюцца ў правіла «Свае сайты напрамую» набору «%@»",
  "«%@» жинағының «Өз сайттарым тікелей» ережесіне қосылады", "«%@» toʻplamidagi «Oʻz saytlarim toʻgʻridan-toʻgʻri» qoidasiga qoʻshiladi",
  "«%@» dəstinin «Öz saytlarım birbaşa» qaydasına əlavə olunur", "«%@» setinin «Kendi sitelerim doğrudan» kuralına eklenir",
  "به قانون «سایت‌های خودم مستقیم» در مجموعهٔ «%@» افزوده می‌شوند", "تُضاف إلى قاعدة «مواقعي مباشرة» في المجموعة «%@»",
  "会添加到规则集“%@”中的“我的网站直连”规则", "Se añaden a la regla «Mis sitios directos» del conjunto «%@»",
  "Kommen in die Regel „Eigene Seiten direkt“ des Satzes „%@“", "Ajoutés à la règle « Mes sites en direct » de l’ensemble « %@ »",
  "Entram na regra “Meus sites direto” do conjunto “%@”")
