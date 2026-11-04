package com.ars3nb.volta.desktop.i18n

/**
 * Every word Volta for Windows shows, in one language. Each language is an object
 * (Lang*.kt) implementing all of it, so a missing string is a compile error rather than
 * an empty label. The Russian set follows the Android app word for word wherever the
 * screens match.
 */
interface Strings {
    // ------------------------------------------------------------ navigation
    val navHome: String
    val navServers: String
    val navSubscriptions: String
    val navSettings: String
    val navAdd: String
    // ------------------------------------------------------------ home
    val tapToConnect: String
    val addSubscriptionFirst: String
    val stateConnecting: String
    val stateConnected: String
    val stateDisconnecting: String
    val stateError: String
    val stateOff: String
    val raisingTunnel: String
    val closingTunnel: String
    val tapToRetry: String
    val tapAgainCancels: String
    val phasePreparing: String
    val phaseProbing: String
    val phaseMeasuring: String
    val phaseRefining: String
    val discConnect: String
    val discDisconnect: String
    val discCancel: String
    val regimeNoNetwork: String
    val addSubscriptionOrServer: String
    val serverNotChosen: String
    val fastestServer: String
    val openListToChoose: String
    val willFindOnConnect: String
    val firstSubscriptionText: String
    val addSubscription: String
    val ms: (Long) -> String
    val speed: String
    val sessionTotals: (String, String) -> String
    val whyThisServer: String
    val checkedAnswered: (Int, Int) -> String
    val chosen: String
    val networkHome: String
    val networkLte: String
    val networkNone: String
    val networkUnknown: String
    val recheckNetwork: String
    val proxyOnTitle: String
    val proxyLocalTitle: String
    val proxyPorts: (Int, Int) -> String
    val lteMissing: String
    // ------------------------------------------------------------ mode selector
    val modeHomeLte: String
    val modeHome: String
    val modeLte: String
    val modeManual: String
    val modeAutoHomeLte: String
    val modeAutoHome: String
    val modeAutoLte: String
    val modeOwnServer: String
    val groupEmpty: String
    val noLteServers: String
    val manualChosen: String
    val backToAuto: String
    val servers: (Int) -> String
    // ------------------------------------------------------------ servers
    val bestInGroup: String
    val pickFind: String
    val pickBest: String
    val searching: String
    val searchPlaceholder: String
    val clearSearch: String
    val poolAll: String
    val poolBalancers: String
    val favorites: String
    val allSubscriptions: String
    val hideBalancers: String
    val hideBalancersHint: String
    val serversAndCountries: (Int, Int) -> String
    val viewCountries: String
    val viewList: String
    val noServersYet: String
    val nothingFound: String
    val addSubOrLink: String
    val changeFilter: String
    val add: String
    val pingAll: String
    val sort: String
    val sortBest: String
    val sortName: String
    val sortSubscription: String
    val addedManually: String
    val notOnWindows: String
    val recentlyFailed: String
    val currentCountry: String
    val fromMs: (Long) -> String
    val collapse: String
    val expand: String
    val balancer: String
    val otherCountries: String
    val actionsHint: String
    val actionConnect: String
    val actionPing: String
    val actionFavorite: String
    val actionUnfavorite: String
    val actionGroupHome: String
    val actionGroupLte: String
    val actionGroupBalancer: String
    val actionCopyLink: String
    val actionDelete: String
    val linkCopied: String
    // ------------------------------------------------------------ subscriptions
    val refreshAll: String
    val refreshSubscription: String
    val noSubsTitle: String
    val noSubsText: String
    val subsHint: String
    val neverUpdated: String
    val updatedJustNow: String
    val updatedMinutes: (Long) -> String
    val updatedHours: (Long) -> String
    val updatedDays: (Long) -> String
    val trafficOf: (String, String) -> String
    val noTrafficLimit: String
    val expired: String
    val expiresToday: String
    val daysLeft: (Int) -> String
    val linkHidden: String
    val subscription: String
    val name: String
    val link: String
    val linkHiddenNote: String
    val save: String
    val cancel: String
    val delete: String
    val deleteSubscriptionQuestion: (String) -> String
    val deleteSubscriptionNote: String
    val copyLink: String
    val supportLink: String
    val providerSite: String
    val keep: String
    val refreshNow: String
    val refreshNowHint: String
    val linkHiddenTitle: String
    // ------------------------------------------------------------ add
    val addTitle: String
    val addSubtitle: String
    val addPlaceholder: String
    val pasteFromClipboard: String
    val clear: String
    val clipboardEmpty: String
    val detEmptyTitle: String
    val detEmptyBody: String
    val detSubHiddenTitle: String
    val detSubHiddenBody: String
    val detSubTitle: String
    val detSubBody: String
    val detEncTitle: String
    val detEncBody: String
    val detServerTitle: (String) -> String
    val detServerBody: String
    val detServersTitle: (Int) -> String
    val detServersBody: String
    val detAmneziaTitle: String
    val detConfTitle: String
    val detConfBody: String
    val detJsonTitle: String
    val detJsonBody: String
    val detControlTitle: String
    val detControlBody: String
    val detUnknownTitle: String
    val detUnknownBody: String
    val detNotOnWindows: (String) -> String
    val nameOptional: String
    val actAddSubscription: String
    val actAddServer: String
    val actAddServers: String
    val actImport: String
    val actRun: String
    val openFile: String
    val openFileHint: String
    val whatCanPaste: String
    val fmtSubTitle: String
    val fmtSubBody: String
    val fmtServerTitle: String
    val fmtServerBody: String
    val fmtVoltaTitle: String
    val fmtVoltaBody: String
    val fmtFileTitle: String
    val fmtFileBody: String
    val fmtWindowsTitle: String
    val fmtWindowsBody: String
    val back: String
    // ------------------------------------------------------------ messages
    val msgAddedServers: (Int) -> String
    val msgSubscriptionAdded: String
    val msgSubscriptionUpdated: (Int) -> String
    val msgRefreshedAll: (Int, Int) -> String
    val msgServerAdded: String
    val msgImported: (Int) -> String
    val msgConnectCancelled: String
    val msgCodeCopied: String
    val msgPingDone: (Int, Int) -> String
    val msgPicked: (String) -> String
    val msgSwitched: (String) -> String
    val msgServerSilent: String
    val msgDeleted: String
    val msgMinimized: String
    val msgProxyFailed: String
    val msgAutostartFailed: String
    val msgTunnelUnchecked: String
    // ------------------------------------------------------------ errors
    val errAddSubscription: String
    val errRefresh: String
    val errNoNet: String
    val errTimeout: String
    val errTls: String
    val errLoad: String
    val errHttp: (Int) -> String
    val errEmpty: String
    val errNoServers: String
    val errOnlyUnsupported: String
    val errPasteLink: String
    val errHttps: String
    val errNotRecognized: String
    val errNothingToImport: String
    val errNoServerToConnect: String
    val errChooseServer: String
    val errCoreMissing: String
    val errCoreStopped: String
    val errCoreStart: (String) -> String
    val errNothingAnswered: String
    val errServerSilent: String
    val errNoInternet: String
    val errGroupEmpty: String
    val errUnsupported: String
    val errLinkIncomplete: String
    val errNotOnWindows: (String) -> String
    val errUnknownType: (String) -> String
    val errNoKey: (String) -> String
    val errNoKeys: (String) -> String
    val errTypeUnsupported: (String) -> String
    val errOldTransport: (String) -> String
    val errSecurityUnsupported: (String) -> String
    val errNoRealityKey: (String) -> String
    val errNoPassword: (String) -> String
    val errOtherInstance: String
    // ------------------------------------------------------------ settings
    val secConnection: String
    val howToPick: String
    val autoConnectTitle: String
    val autoConnectHint: String
    val failoverTitle: String
    val failoverHint: String
    val systemProxyTitle: String
    val systemProxyHint: String
    val localPortsLine: (Int, Int) -> String
    val secWindows: String
    val autostartTitle: String
    val autostartHint: String
    val trayTitle: String
    val trayHint: String
    val secChecks: String
    val refreshOnLaunchTitle: String
    val refreshOnLaunchHint: String
    val probeOnStartTitle: String
    val probeOnStartHint: String
    val howToCheck: String
    val pingReal: String
    val pingTcp: String
    val pingRealHint: String
    val pingTcpHint: String
    val secTunnel: String
    val localDnsTitle: String
    val localDnsHint: String
    val routingTitle: String
    val routingAll: String
    val routingAllHint: String
    val routingRu: String
    val routingRuHint: String
    val routingProvider: (String) -> String
    val secLook: String
    val theme: String
    val themeSystem: String
    val themeVolta: String
    val themeLight: String
    val themeOcean: String
    val themeEmber: String
    val language: String
    val languageSystem: String
    val secDevice: String
    val hwidTitle: String
    val hwidHint: String
    val deviceCode: String
    val copyCode: String
    val secAbout: String
    val version: (String) -> String
    val coreVersion: (String) -> String
    val helpTitle: String
    val helpHint: String
    val dataFolder: String
    val dataFolderHint: String
    // ------------------------------------------------------------ tray
    val trayOpen: String
    val trayConnect: String
    val trayDisconnect: String
    val trayExit: String
    val trayTooltipOn: (String) -> String
    val trayTooltipOff: String
    // ------------------------------------------------------------ units
    /** B, KB, MB, GB, TB. */
    val byteUnits: List<String>
    val perSecond: String
}
