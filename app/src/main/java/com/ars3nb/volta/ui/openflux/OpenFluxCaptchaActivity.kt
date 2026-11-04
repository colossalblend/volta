package com.ars3nb.volta.ui.openflux

import android.annotation.SuppressLint
import android.content.Context
import com.ars3nb.volta.core.locale.AppLanguage
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.ars3nb.volta.core.openflux.OpenFluxRunner

/**
 * A captcha or login page an OpenFlux carrier ran into (Yandex SmartCaptcha, mostly).
 * The user passes it here and the page's cookies go back to the carrier, which retries
 * with them. When the check is the exit's, the page is opened through the helper's
 * proxy, so it is passed from the exit's address, where the carrier needs it.
 */
class OpenFluxCaptchaActivity : Activity() {

    /** In the language picked in Settings (Android 12 and older; 13+ does it itself). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    private var web: WebView? = null
    private var proxied = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val captcha = OpenFluxRunner.status.value as? OpenFluxRunner.Status.Captcha
        if (captcha == null || !captcha.url.startsWith("http")) {
            Toast.makeText(
                this,
                if (captcha == null) "Проверка больше не нужна" else "Эту проверку Volta пока не открывает",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        web = view
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val hint = TextView(this).apply {
            text = buildString {
                append("Пройдите проверку на странице и нажмите «Готово».")
                if (captcha.proxy.isNotBlank()) append(" Страница открыта через сервер OpenFlux.")
            }
            setPadding(pad, pad, pad, pad / 2)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(pad, pad / 2, pad, pad)
            addView(Button(this@OpenFluxCaptchaActivity).apply {
                text = "Отмена"
                setOnClickListener {
                    OpenFluxRunner.cancelCaptcha()
                    finish()
                }
            })
            addView(Button(this@OpenFluxCaptchaActivity).apply {
                text = "Готово"
                setOnClickListener { submit(captcha.url) }
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
            // Edge to edge: keep the text and buttons clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)

        if (captcha.proxy.isNotBlank() && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxied = true
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule(captcha.proxy).build(),
                ContextCompat.getMainExecutor(this)
            ) { view.loadUrl(captcha.url) }
        } else {
            view.loadUrl(captcha.url)
        }
    }

    private fun submit(url: String) {
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        if (cookies.isBlank()) {
            Toast.makeText(this, "Страница ещё не пропустила: пройдите проверку до конца", Toast.LENGTH_LONG)
                .show()
            return
        }
        CookieManager.getInstance().flush()
        OpenFluxRunner.submitCookies(cookies)
        finish()
    }

    override fun onDestroy() {
        if (proxied) {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) {}
            }
        }
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
