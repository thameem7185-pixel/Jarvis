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
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

// ==== CHANGE THIS to your GitHub Pages URL (or "file:///android_asset/index.html"
// if you bundle the IDE locally in app/src/main/assets/) ====
private const val IDE_URL = "https://thameem7185-pixel.github.io/Arduino_Web_IDE/"

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var pendingBaud: Int = 9600
    private var pendingConnectCallback: (() -> Unit)? = null

    private val ACTION_USB_PERMISSION = "com.thameem.arduinoide.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        openPort(device, pendingBaud)
                    } else {
                        runOnJs("log('error', '✗ USB permission denied.')")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(SerialBridge(), "AndroidSerial")
        webView.loadUrl(IDE_URL)
    }

    // ===================== JS <-> Native bridge =====================
    inner class SerialBridge {

        @JavascriptInterface
        fun connect(baudRate: Int): Boolean {
            pendingBaud = baudRate

            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) {
                runOnJs("log('error', '✗ No compatible USB serial device found. Check the cable and that USB debugging/OTG is enabled.')")
                return false
            }

            val driver: UsbSerialDriver = drivers[0]
            val device = driver.device

            if (!usbManager.hasPermission(device)) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(
                    this@MainActivity, 0, Intent(ACTION_USB_PERMISSION), flags
                )
                usbManager.requestPermission(device, permissionIntent)
                // Result arrives asynchronously in usbReceiver -> openPort()
                return true
            }

            return openPort(device, baudRate)
        }

        @JavascriptInterface
        fun disconnect() {
            try {
                ioManager?.stop()
                serialPort?.close()
            } catch (e: Exception) {
                Log.e("ArduinoIDE", "disconnect error", e)
            }
            ioManager = null
            serialPort = null
        }

        @JavascriptInterface
        fun write(data: String) {
            try {
                serialPort?.write(data.toByteArray(Charsets.UTF_8), 1000)
            } catch (e: Exception) {
                runOnJs("log('error', 'Write failed: ${e.message}')")
            }
        }
    }

    private fun openPort(device: UsbDevice, baudRate: Int): Boolean {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull { it.device == device }
        if (driver == null) {
            runOnJs("log('error', '✗ Could not match a driver to this USB device.')")
            return false
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            runOnJs("log('error', '✗ Failed to open USB connection (permission issue?).')")
            return false
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            runOnJs("log('error', 'Failed to open port: ${e.message}')")
            return false
        }

        serialPort = port
        ioManager = SerialInputOutputManager(port, this).also { executor.submit(it) }

        runOnUiThread {
            webView.evaluateJavascript(
                "state.connected = true; setConnectedUI(true); log('success', '✓ Connected via native USB bridge!');",
                null
            )
        }
        return true
    }

    // SerialInputOutputManager.Listener — runs on the executor thread
    override fun onNewData(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        runOnJs("onNativeSerialData(${jsStringLiteral(text)})")
    }

    override fun onRunError(e: Exception) {
        runOnJs("log('error', 'Serial connection lost: ${e.message}')")
    }

    private fun runOnJs(js: String) {
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    private fun jsStringLiteral(s: String): String {
        // Safely embed arbitrary serial text as a JS string literal
        val escaped = s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            ioManager?.stop()
            serialPort?.close()
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}
