using System.Globalization;

namespace Volta.Setup
{
    /// <summary>The installer's words, in Russian or English after the Windows display language.</summary>
    internal sealed class Text
    {
        public static Text T { get; private set; } = En();
        public static bool Ru { get; private set; }

        public static void Choose(string forced)
        {
            string lang = forced ?? CultureInfo.CurrentUICulture.TwoLetterISOLanguageName;
            Ru = lang == "ru" || lang == "uk" || lang == "be" || lang == "kk" || lang == "uz" || lang == "ky" || lang == "tg";
            T = Ru ? RuText() : En();
        }

        public string WindowTitle, UninstallWindowTitle, Tagline, VersionLine;
        public string Next, Back, Cancel, Install, Update, Close, Launch, Browse, Remove, OpenLog;
        public string WelcomeTitle, WelcomeUpdateTitle, WelcomeBody, NoServersTitle, NoServersBody;
        public string FactNoAdmin, FactWindows, FactSizeFormat;
        public string UpdateFromTo, UpdateKeeps, Reinstall;
        public string PlaceTitle, PlaceBody, PlaceSpaceFormat, PlaceFolderPicker, OptDesktop, OptStartMenu, OptAutostart, OptAutostartHint;
        public string ProgressInstall, ProgressUpdate, ProgressUninstall, ProgressNote;
        public string DoneTitle, DoneBody, DoneUpdatedFormat, DoneNote;
        public string UninstallTitle, UninstallBody, RemoveDataQuestion, RemoveDataHint, UninstallDoneTitle, UninstallDoneKept, UninstallDoneRemoved;
        public string StatusPreparing, StatusClosing, StatusOldVersion, StatusRemovingOld, StatusCopying, StatusShortcuts, StatusDone, StatusRemovingFiles, StatusRemovingData;
        public string ErrorTitle, ErrorInstall, ErrorUninstall, ErrorUnexpected, ErrorLocked, ErrorFolder, LogLine;
        public string StepWelcome, StepPlace, StepInstall, StepDone, StepConfirm, StepRemoving, StepUpdate;

        private static Text RuText() => new Text
        {
            WindowTitle = "Установка Volta",
            UninstallWindowTitle = "Удаление Volta",
            Tagline = "VPN-клиент для Windows",
            VersionLine = "Версия {0}",
            Next = "Далее",
            Back = "Назад",
            Cancel = "Отмена",
            Install = "Установить",
            Update = "Обновить",
            Close = "Закрыть",
            Launch = "Запустить Volta",
            Browse = "Обзор…",
            Remove = "Удалить",
            OpenLog = "Открыть журнал",
            WelcomeTitle = "Добро пожаловать в Volta",
            WelcomeUpdateTitle = "Обновление Volta",
            WelcomeBody = "Volta подключает компьютер к серверам вашего VPN-сервиса через ядро Xray. Она сама включает системный прокси Windows и всегда возвращает прежние настройки сети.",
            NoServersTitle = "В приложении нет своих серверов — нужна ссылка от VPN-сервиса",
            NoServersBody = "Подписку или ключ выдаёт ваш VPN-сервис. После установки вставьте ссылку в Volta и нажмите на медный диск.",
            FactNoAdmin = "Без прав администратора",
            FactWindows = "Windows 10 и 11",
            FactSizeFormat = "{0} на диске",
            UpdateFromTo = "Обновление до {1}",
            UpdateKeeps = "Сейчас установлена версия {0}. Подписки, серверы и настройки останутся на месте: их папку установщик не трогает.",
            Reinstall = "Переустановка версии {0}",
            PlaceTitle = "Куда установить",
            PlaceBody = "Volta ставится только для вас — права администратора не нужны.",
            PlaceSpaceFormat = "Нужно {0} · свободно {1}",
            PlaceFolderPicker = "Папка для Volta",
            OptDesktop = "Ярлык на рабочем столе",
            OptStartMenu = "Ярлык в меню «Пуск»",
            OptAutostart = "Запускать вместе с Windows",
            OptAutostartHint = "Volta будет ждать в трее у часов",
            ProgressInstall = "Устанавливаем Volta",
            ProgressUpdate = "Обновляем Volta",
            ProgressUninstall = "Удаляем Volta",
            ProgressNote = "Подписки и настройки в %APPDATA%\\Volta не затрагиваются.",
            DoneTitle = "Готово!",
            DoneBody = "Volta установлена. Добавьте ссылку от VPN-сервиса и нажмите на медный диск — Volta подключится.",
            DoneUpdatedFormat = "Volta обновлена до версии {0}. Подписки и настройки на месте.",
            DoneNote = "Volta есть в меню «Пуск». Крестик окна прячет её в трей — VPN продолжает работать.",
            UninstallTitle = "Удалить Volta?",
            UninstallBody = "Volta закроется, а системный прокси Windows вернётся к прежним настройкам. Ярлыки, автозапуск и ссылки volta:// будут удалены.",
            RemoveDataQuestion = "Удалить также подписки и настройки?",
            RemoveDataHint = "Папка %APPDATA%\\Volta: подписки, серверы, маршруты и журналы. Если оставить, после новой установки всё будет на месте.",
            UninstallDoneTitle = "Volta удалена",
            UninstallDoneKept = "Подписки и настройки сохранены в %APPDATA%\\Volta — при новой установке Volta их подхватит.",
            UninstallDoneRemoved = "Программа, подписки и настройки удалены. Спасибо, что пользовались Volta.",
            StatusPreparing = "Подготовка…",
            StatusClosing = "Закрываем Volta…",
            StatusOldVersion = "Убираем прежний установщик…",
            StatusRemovingOld = "Убираем старые файлы…",
            StatusCopying = "Копируем файлы…",
            StatusShortcuts = "Ярлыки и настройки Windows…",
            StatusDone = "Готово",
            StatusRemovingFiles = "Удаляем файлы программы…",
            StatusRemovingData = "Удаляем подписки и настройки…",
            ErrorTitle = "Не получилось",
            ErrorInstall = "Установка не завершилась. Ваши подписки и настройки не пострадали.",
            ErrorUninstall = "Удаление не завершилось.",
            ErrorUnexpected = "Установщик столкнулся с ошибкой.",
            ErrorLocked = "Файл занят другой программой:",
            ErrorFolder = "В эту папку нельзя установить Volta. Выберите другую.",
            LogLine = "Журнал: {0}",
            StepWelcome = "Начало",
            StepPlace = "Папка",
            StepInstall = "Установка",
            StepDone = "Готово",
            StepConfirm = "Удаление",
            StepRemoving = "Удаляем",
            StepUpdate = "Обновление",
        };

        private static Text En() => new Text
        {
            WindowTitle = "Volta Setup",
            UninstallWindowTitle = "Uninstall Volta",
            Tagline = "VPN client for Windows",
            VersionLine = "Version {0}",
            Next = "Next",
            Back = "Back",
            Cancel = "Cancel",
            Install = "Install",
            Update = "Update",
            Close = "Close",
            Launch = "Start Volta",
            Browse = "Browse…",
            Remove = "Uninstall",
            OpenLog = "Open the log",
            WelcomeTitle = "Welcome to Volta",
            WelcomeUpdateTitle = "Updating Volta",
            WelcomeBody = "Volta connects this computer to your VPN service's servers through the Xray core. It switches the Windows proxy on by itself and always puts your network settings back.",
            NoServersTitle = "Volta has no servers of its own — you need a link from a VPN service",
            NoServersBody = "Your VPN service gives you a subscription link or key. After installing, paste it into Volta and press the copper disc.",
            FactNoAdmin = "No administrator rights",
            FactWindows = "Windows 10 and 11",
            FactSizeFormat = "{0} on disk",
            UpdateFromTo = "Update to {1}",
            UpdateKeeps = "Version {0} is installed now. Your subscriptions, servers and settings stay: the installer never touches their folder.",
            Reinstall = "Reinstalling version {0}",
            PlaceTitle = "Where to install",
            PlaceBody = "Volta installs just for you — no administrator rights needed.",
            PlaceSpaceFormat = "Needs {0} · {1} free",
            PlaceFolderPicker = "Folder for Volta",
            OptDesktop = "Desktop shortcut",
            OptStartMenu = "Start menu shortcut",
            OptAutostart = "Start with Windows",
            OptAutostartHint = "Volta waits in the tray by the clock",
            ProgressInstall = "Installing Volta",
            ProgressUpdate = "Updating Volta",
            ProgressUninstall = "Uninstalling Volta",
            ProgressNote = "Subscriptions and settings in %APPDATA%\\Volta are not touched.",
            DoneTitle = "All set!",
            DoneBody = "Volta is installed. Add the link from your VPN service and press the copper disc — Volta connects.",
            DoneUpdatedFormat = "Volta is updated to {0}. Your subscriptions and settings are all there.",
            DoneNote = "Volta is in the Start menu. Closing the window hides it in the tray — the VPN keeps running.",
            UninstallTitle = "Uninstall Volta?",
            UninstallBody = "Volta will close and the Windows proxy goes back to how it was. Shortcuts, autostart and volta:// links are removed.",
            RemoveDataQuestion = "Also remove subscriptions and settings?",
            RemoveDataHint = "The %APPDATA%\\Volta folder: subscriptions, servers, routes and logs. Keep it and a new install finds everything in place.",
            UninstallDoneTitle = "Volta is uninstalled",
            UninstallDoneKept = "Subscriptions and settings are kept in %APPDATA%\\Volta — a new install picks them up.",
            UninstallDoneRemoved = "The app, its subscriptions and settings are removed. Thank you for using Volta.",
            StatusPreparing = "Getting ready…",
            StatusClosing = "Closing Volta…",
            StatusOldVersion = "Removing the old installer…",
            StatusRemovingOld = "Removing old files…",
            StatusCopying = "Copying files…",
            StatusShortcuts = "Shortcuts and Windows settings…",
            StatusDone = "Done",
            StatusRemovingFiles = "Removing the app's files…",
            StatusRemovingData = "Removing subscriptions and settings…",
            ErrorTitle = "Something went wrong",
            ErrorInstall = "The installation did not finish. Your subscriptions and settings are safe.",
            ErrorUninstall = "Uninstalling did not finish.",
            ErrorUnexpected = "The installer ran into an error.",
            ErrorLocked = "A file is in use by another program:",
            ErrorFolder = "Volta can't be installed into this folder. Pick another one.",
            LogLine = "Log: {0}",
            StepWelcome = "Welcome",
            StepPlace = "Folder",
            StepInstall = "Install",
            StepDone = "Done",
            StepConfirm = "Uninstall",
            StepRemoving = "Removing",
            StepUpdate = "Update",
        };

        public static string Size(long bytes)
        {
            var c = Ru ? new CultureInfo("ru-RU") : CultureInfo.InvariantCulture;
            if (bytes >= 1L << 30) return (bytes / (double)(1L << 30)).ToString("0.#", c) + (Ru ? " ГБ" : " GB");
            return (bytes / (double)(1L << 20)).ToString("0", c) + (Ru ? " МБ" : " MB");
        }
    }
}
