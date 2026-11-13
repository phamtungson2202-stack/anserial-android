package com.example.canserial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        // >>> SỬA DÒNG NÀY thành địa chỉ trang web của bạn <<<
        const val APP_URL = "https://phamtungson2202-stack.github.io/can-dien-tu/"
        const val ACTION_USB_PERMISSION = "com.example.canserial.USB_PERMISSION"
    }

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private lateinit var polyfill: String

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var pendingOpen: ((Boolean) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val cb = pendingOpen
                pendingOpen = null
                cb?.invoke(granted)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        polyfill = assets.open("serial-polyfill.js").bufferedReader().use { it.readText() }

        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        webView = WebView(this)
        setContentView(webView)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val appUri = Uri.parse(APP_URL)
        val origin = "${appUri.scheme}://${appUri.host}"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true // để trang lưu lịch sử bằng localStorage
        webView.addJavascriptInterface(Bridge(), "AndroidSerial")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == appUri.host) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url)) // link ngoài mở bằng trình duyệt
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && Uri.parse(url).host == appUri.host) {
                    view.evaluateJavascript(polyfill, null)
                }
            }
        }

        // Chèn polyfill sớm nhất có thể (trước script của trang)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, polyfill, setOf(origin))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl(APP_URL)
    }

    override fun onDestroy() {
        closePort()
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    // ---------- Gọi JS ----------
    private fun js(code: String) = runOnUiThread { webView.evaluateJavascript(code, null) }

    private fun resolve(id: Int, ok: Boolean, msg: String = "") =
        js("window.__serialResolve&&window.__serialResolve($id,$ok,${JSONObject.quote(msg)})")

    private fun notifyClosed() {
        closePort()
        js("window.__serialClosed&&window.__serialClosed()")
    }

    // ---------- Cổng serial ----------
    private fun findDriver(deviceName: String): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceName == deviceName }

    private fun openPort(id: Int, driver: UsbSerialDriver, baud: Int, dataBits: Int, stopBits: Int, parity: String) {
        try {
            closePort()
            val conn = usbManager.openDevice(driver.device) ?: throw IOException("Không mở được thiết bị USB")
            val p = driver.ports[0]
            p.open(conn)
            val sb = if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1
            val pa = when (parity) {
                "odd" -> UsbSerialPort.PARITY_ODD
                "even" -> UsbSerialPort.PARITY_EVEN
                else -> UsbSerialPort.PARITY_NONE
            }
            p.setParameters(baud, dataBits, sb, pa)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
            port = p

            resolve(id, true) // báo thành công trước, rồi mới bắt đầu đẩy dữ liệu
            val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                    js("window.__serialData&&window.__serialData('$b64')")
                }
                override fun onRunError(e: Exception) {
                    notifyClosed()
                }
            })
            ioManager = mgr
            mgr.start()
        } catch (e: Exception) {
            closePort()
            resolve(id, false, e.message ?: "Lỗi mở cổng")
        }
    }

    private fun closePort() {
        try { ioManager?.listener = null; ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    // ---------- Cầu nối JS <-> Android ----------
    inner class Bridge {
        @JavascriptInterface
        fun listDevices(): String {
            val arr = JSONArray()
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).forEach { d ->
                arr.put(
                    JSONObject()
                        .put("vid", d.device.vendorId)
                        .put("pid", d.device.productId)
                        .put("name", d.device.deviceName)
                )
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun open(id: Int, deviceName: String, baud: Int, dataBits: Int, stopBits: Int, parity: String) {
            val driver = findDriver(deviceName)
            if (driver == null) {
                resolve(id, false, "Không thấy thiết bị USB-Serial")
                return
            }
            if (usbManager.hasPermission(driver.device)) {
                openPort(id, driver, baud, dataBits, stopBits, parity)
            } else {
                pendingOpen = { granted ->
                    if (granted) openPort(id, driver, baud, dataBits, stopBits, parity)
                    else resolve(id, false, "Chưa cấp quyền USB cho app")
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    this@MainActivity, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
                )
                usbManager.requestPermission(driver.device, pi)
            }
        }

        @JavascriptInterface
        fun write(b64: String) {
            try {
                port?.write(Base64.decode(b64, Base64.DEFAULT), 2000)
            } catch (e: Exception) {
                notifyClosed()
            }
        }

        @JavascriptInterface
        fun close() {
            closePort()
        }
    }
}
