package com.thameem.arduinoide

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val ACTION_USB_PERMISSION = "com.thameem.arduinoide.USB_PERMISSION"

    // ============ Permission result receiver ============
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openConnection(it) }
                    } else {
                        notifyJs("onError", "Permission denied for USB device.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        webView = findViewById(R.id.webview)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(UsbBridge(), "AndroidUSB")

        // Loads your existing HTML IDE from the assets folder.
        // Copy your arduino-web-ide-2.html into app/src/main/assets/index.html
        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnection()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    // ============ Called from JavaScript (window.AndroidUSB.xxx) ============
    inner class UsbBridge {

        @JavascriptInterface
        fun requestConnection() {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) {
                notifyJs("onError", "No USB serial device found. Check the cable and OTG.")
                return
            }
            val driver: UsbSerialDriver = drivers[0]
            val device = driver.device

            if (usbManager.hasPermission(device)) {
                openConnection(device)
            } else {
                val pendingIntent = PendingIntent.getBroadcast(
                    this@MainActivity, 0, Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(device, pendingIntent)
            }
        }

        @JavascriptInterface
        fun write(data: String) {
            try {
                serialPort?.write(data.toByteArray(), 1000)
            } catch (e: Exception) {
                notifyJs("onError", "Write failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun disconnect() {
            closeConnection()
        }
    }

    // ============ Connection handling ============
    private fun openConnection(device: UsbDevice) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device.deviceId == device.deviceId } ?: return

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            notifyJs("onError", "Failed to open USB device connection.")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port

            ioManager = SerialInputOutputManager(port, this)
            executor.submit(ioManager)

            notifyJs("onConnected", "")
        } catch (e: Exception) {
            notifyJs("onError", "Failed to open port: ${e.message}")
        }
    }

    private fun closeConnection() {
        try {
            ioManager?.stop()
            serialPort?.close()
        } catch (_: Exception) {}
        serialPort = null
        ioManager = null
    }

    // ============ Incoming data from Arduino -> pushed into JS ============
    override fun onNewData(data: ByteArray) {
        val text = String(data)
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        runOnUiThread {
            webView.evaluateJavascript("window.onAndroidUsbData && window.onAndroidUsbData('$escaped');", null)
        }
    }

    override fun onRunError(e: Exception) {
        notifyJs("onError", "Connection lost: ${e.message}")
    }

    private fun notifyJs(fn: String, message: String) {
        val safeMsg = message.replace("'", "\\'")
        runOnUiThread {
            webView.evaluateJavascript("window.$fn && window.$fn('$safeMsg');", null)
        }
    }
}
