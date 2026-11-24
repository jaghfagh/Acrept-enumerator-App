package com.dunegis.acrept.enumerator

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * ACREPT Enumerator: hosts the offline web app (assets/www/index.html) in a WebView
 * and gives it native GPS, camera, SMS and network-status features through "AndroidBridge".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingLocation = false

    // The app is served from this https origin, so localStorage persists and fetch() to the ACREPT server works.
    private val appUrl = "https://appassets.androidplatform.net/assets/www/index.html"

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            if (pendingLocation) fetchLocation()
        } else {
            Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
        }
        pendingLocation = false
    }

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        if (result.resultCode != RESULT_OK) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val picked = result.data?.data
        val shot = cameraUri
        when {
            picked != null -> cb.onReceiveValue(arrayOf(picked))
            shot != null -> cb.onReceiveValue(arrayOf(shot))
            else -> cb.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setGeolocationEnabled(false) // location comes from the native bridge
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                // sms:, tel:, external links open in the right app
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                } catch (e: ActivityNotFoundException) { true }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                launchPhotoChooser(params)
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let the web app go back one screen; close the app on the home screen.
                webView.evaluateJavascript(
                    "(function(){var b=document.querySelector('[data-act=back]');if(b){b.click();return true}return false})()"
                ) { handled -> if (handled != "true") finish() }
            }
        })

        watchNetwork()
        if (savedInstanceState == null) webView.loadUrl(appUrl) else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /** Camera (saved to app cache) plus gallery, in one chooser. */
    private fun launchPhotoChooser(params: WebChromeClient.FileChooserParams) {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File.createTempFile("measure_", ".jpg", dir)
        cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(params.createIntent(), "Photo of the measure")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        try {
            pickPhoto.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null); fileCallback = null
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!hasLocationPermission()) {
            pendingLocation = true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                Toast.makeText(this, "Turn on Location in phone settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return
            }
        }
        Toast.makeText(this, "Getting GPS fix…", Toast.LENGTH_SHORT).show()
        LocationManagerCompat.getCurrentLocation(
            lm, provider, CancellationSignal(), ContextCompat.getMainExecutor(this)
        ) { loc: Location? ->
            if (loc != null) {
                webView.evaluateJavascript("window.acreptOnLocation(${loc.latitude},${loc.longitude},${loc.accuracy})", null)
            } else {
                Toast.makeText(this, "No GPS fix. Move into the open and try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = push(true)
            override fun onLost(network: Network) = push(false)
            private fun push(on: Boolean) = runOnUiThread {
                webView.evaluateJavascript("window.acreptOnNetwork && window.acreptOnNetwork($on)", null)
            }
        })
    }

    /** Methods the web app calls as window.AndroidBridge.* */
    inner class Bridge {
        @JavascriptInterface
        fun requestLocation() = runOnUiThread { fetchLocation() }

        @JavascriptInterface
        fun sendSms(number: String, body: String) = runOnUiThread {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                .putExtra("sms_body", body)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_sms_app, Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME
    }
}
