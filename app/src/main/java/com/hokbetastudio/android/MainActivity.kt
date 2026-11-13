package com.hokbetastudio.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import android.net.Uri
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private var pendingOpenId: String? = null
    private var generation = 0

    companion object {
        private const val PICK_REQUEST = 4101
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            addJavascriptInterface(AndroidBridge(), "HokAndroid")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    return request.url.host != "appassets.androidplatform.net"
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/index.html")
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            try {
                val request = JSONObject(message)
                val id = request.getString("id")
                val method = request.getString("method")
                val payload = request.optJSONObject("payload") ?: JSONObject()

                runOnUiThread {
                    dispatch(id, method, payload)
                }
            } catch (e: Exception) {
                // Malformed messages are ignored; no filesystem action is taken.
            }
        }
    }

    private fun dispatch(
        id: String,
        method: String,
        payload: JSONObject
    ) {
        when (method) {
            "open" -> openPicker(id, payload.optString("mode", "files"))

            "clear" -> {
                generation++
                sendSuccess(id, JSONObject())
            }

            else -> sendError(
                id,
                "ANDROID_NOT_IMPLEMENTED: $method is not available yet. " +
                    "Android package scanning is implemented first; " +
                    "asset parsing and export are still pending."
            )
        }
    }

    private fun openPicker(id: String, mode: String) {
        if (pendingOpenId != null) {
            sendError(id, "A file selection is already in progress.")
            return
        }

        if (mode != "folder") {
            showFileAppChooser(id)
            return
        }

        pendingOpenId = id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, PICK_REQUEST)
        } catch (e: Exception) {
            pendingOpenId = null
            sendError(id, "Cannot open folder picker: ${e.message}")
        }
    }

    private fun showFileAppChooser(id: String) {
        val queries = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        )

        val discovered = linkedMapOf<String, Pair<String, Intent>>()

        for (query in queries) {
            val matches = packageManager.queryIntentActivities(
                query,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )

            for (resolve in matches) {
                val activity = resolve.activityInfo ?: continue
                val key = "${activity.packageName}/${activity.name}"

                if (discovered.containsKey(key)) continue

                val launchIntent = Intent(query).apply {
                    component = android.content.ComponentName(
                        activity.packageName,
                        activity.name
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                discovered[key] = Pair(
                    resolve.loadLabel(packageManager).toString(),
                    launchIntent
                )
            }
        }

        val apps = discovered.values.sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            sendError(id, "No compatible file-selection apps were found.")
            return
        }

        pendingOpenId = id

        android.app.AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(apps.map { it.first }.toTypedArray()) { _, index ->
                try {
                    startActivityForResult(apps[index].second, PICK_REQUEST)
                } catch (e: Exception) {
                    pendingOpenId = null
                    sendError(id, "Cannot open selected app: ${e.message}")
                }
            }
            .setOnCancelListener {
                if (pendingOpenId == id) {
                    pendingOpenId = null
                    sendSuccess(id, null)
                }
            }
            .show()
    }

    @Deprecated("Uses the compatible Activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != PICK_REQUEST) return

        val id = pendingOpenId ?: return
        pendingOpenId = null

        if (resultCode != RESULT_OK || data == null) {
            sendSuccess(id, null)
            return
        }

        val isTree = data.data?.let {
            DocumentsHelper.isTreeUri(it)
        } ?: false

        val selected = mutableListOf<Pair<Uri, Boolean>>()

        if (isTree) {
            data.data?.let { selected.add(it to true) }
        } else {
            val clip = data.clipData
            if (clip != null) {
                for (index in 0 until clip.itemCount) {
                    selected.add(clip.getItemAt(index).uri to false)
                }
            } else {
                data.data?.let { selected.add(it to false) }
            }
        }

        if (selected.isEmpty()) {
            sendSuccess(id, null)
            return
        }

        for ((uri, _) in selected) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some document providers do not support persistent grants.
            }
        }

        scannerExecutor.execute {
            try {
                val result = PackageScanner(this).scan(selected)
                val response = JSONObject().apply {
                    put("generation", ++generation)
                    put("files", result.getJSONArray("files"))
                    put("errors", result.getJSONArray("errors"))
                    put("visited", result.getInt("visited"))
                }
                sendSuccess(id, response)
            } catch (e: Exception) {
                sendError(id, "Package scan failed: ${e.message}")
            }
        }
    }

    private fun sendSuccess(id: String, data: JSONObject?) {
        val response = JSONObject().apply {
            put("id", id)
            put("ok", true)
            put("data", data ?: JSONObject.NULL)
        }
        sendToWeb(response)
    }

    private fun sendError(id: String, error: String) {
        val response = JSONObject().apply {
            put("id", id)
            put("ok", false)
            put("error", error)
        }
        sendToWeb(response)
    }

    private fun sendToWeb(response: JSONObject) {
        runOnUiThread {
            if (!::webView.isInitialized) return@runOnUiThread
            val encoded = JSONObject.quote(response.toString())
            webView.evaluateJavascript(
                "window.__hokAndroidMessage && " +
                    "window.__hokAndroidMessage($encoded);",
                null
            )
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        scannerExecutor.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}

private object DocumentsHelper {
    fun isTreeUri(uri: Uri): Boolean {
        return uri.toString().contains("/tree/")
    }
}
