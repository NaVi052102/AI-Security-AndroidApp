package com.example.aisecurity.ui.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ble.CaseManager
import com.example.aisecurity.ble.WatchManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class BluetoothFragment : Fragment() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceAdapter: BleDeviceAdapter
    private var isScanning = false
    private var isUpdatingSwitch = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val uid = FirebaseAuth.getInstance().currentUser?.uid

                if (state == BluetoothAdapter.STATE_OFF) {
                    updateToggleState()
                    deviceAdapter.clear()

                    // 🚨 NEW: Sync to Firebase instantly so remote controllers don't fight it
                    if (uid != null) {
                        FirebaseFirestore.getInstance().collection("Users").document(uid).set(
                            hashMapOf("state_bluetooth" to false), SetOptions.merge()
                        )
                    }

                    if (isScanning) {
                        isScanning = false
                        view?.findViewById<Button>(R.id.btnScan)?.let { updateScanButtonUI(it, false) }
                    }
                } else if (state == BluetoothAdapter.STATE_ON) {
                    updateToggleState()

                    // 🚨 NEW: Sync to Firebase instantly
                    if (uid != null) {
                        FirebaseFirestore.getInstance().collection("Users").document(uid).set(
                            hashMapOf("state_bluetooth" to true), SetOptions.merge()
                        )
                    }
                }
            }
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateToggleState()
    }

    private val requestTogglePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            try {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) { Log.e("BLE", "Error enabling Bluetooth", e) }
        } else {
            showSentryToast("Permission needed to enable Bluetooth.", isLong = false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) startRadarScan()
        else showSentryToast("Permissions required to scan.", isLong = false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bluetooth, container, false)
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val componentName = android.content.ComponentName(requireContext(), com.example.aisecurity.ble.WatchMediaService::class.java)
        requireContext().packageManager.setComponentEnabledSetting(
            componentName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        requireContext().packageManager.setComponentEnabledSetting(
            componentName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )

        val isNotificationAccessGranted = NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().packageName)
        if (!isNotificationAccessGranted) {
            showSentryToast("Please ALLOW Notification Access for Music Sync!", isLong = true)
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val switchBluetooth = view.findViewById<SwitchCompat>(R.id.switchBluetooth)
        val btnScan = view.findViewById<Button>(R.id.btnScan)
        val btnSetPin = view.findViewById<Button>(R.id.btnSetPin)
        val recyclerDevices = view.findViewById<RecyclerView>(R.id.recyclerDevices)

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        btnSetPin.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            if (isNightMode) {
                colors = intArrayOf(Color.parseColor("#1E3A8A"), Color.parseColor("#172554"))
                setStroke(4, Color.parseColor("#3B82F6"))
            } else {
                colors = intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"))
            }
        }
        btnSetPin.setOnClickListener { showSetPinDialog() }

        updateScanButtonUI(btnScan, isScanning)

        deviceAdapter = BleDeviceAdapter { clickedDevice, resolvedName, isAlreadyConnected ->
            if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
                showSentryToast("Please turn on Bluetooth first!", isLong = false)
                return@BleDeviceAdapter
            }

            val isTargetCase = resolvedName.contains("Sentry Case", true) ||
                    resolvedName.contains("ESP32", true) ||
                    clickedDevice.address == "80:F3:DA:63:90:7E" ||
                    prefs.getString("saved_case_mac", "") == clickedDevice.address

            if (isTargetCase) {
                if (isAlreadyConnected) {
                    CaseManager.disconnect()
                    prefs.edit().remove("saved_case_mac").apply()
                    showSentryToast("Case Disconnected", isLong = false)
                } else {
                    prefs.edit().putString("saved_case_mac", clickedDevice.address).apply()
                    stopRadarScan()
                    updateScanButtonUI(btnScan, false)
                    showSentryToast("Connecting to Sentry Case...", isLong = false)
                    CaseManager.connectToTarget(requireContext(), clickedDevice.address)
                }
            } else {
                if (isAlreadyConnected) {
                    WatchManager.disconnect()
                    prefs.edit().remove("saved_watch_mac").apply()
                    showSentryToast("Watch Disconnected", isLong = false)
                } else {
                    prefs.edit().putString("saved_watch_mac", clickedDevice.address).apply()
                    stopRadarScan()
                    updateScanButtonUI(btnScan, false)
                    showSentryToast("Connecting to Watch Pro...", isLong = false)
                    WatchManager.connectToTarget(requireContext(), clickedDevice.address)
                }
            }
        }

        recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        recyclerDevices.adapter = deviceAdapter

        WatchManager.isConnected.observe(viewLifecycleOwner) { isConnected ->
            val mac = prefs.getString("saved_watch_mac", null)
            if (mac != null) deviceAdapter.updateConnectionState(mac, isConnected)
        }

        CaseManager.isConnected.observe(viewLifecycleOwner) { isConnected ->
            val mac = CaseManager.connectedMacAddress ?: prefs.getString("saved_case_mac", null)
            if (mac != null) deviceAdapter.updateConnectionState(mac, isConnected)
        }

        val savedWatchMac = prefs.getString("saved_watch_mac", null)
        val savedCaseMac = prefs.getString("saved_case_mac", null)

        if (::bluetoothAdapter.isInitialized && bluetoothAdapter.isEnabled) {
            try {
                if (savedWatchMac != null && WatchManager.isConnected.value == true) {
                    val savedDevice = bluetoothAdapter.getRemoteDevice(savedWatchMac)
                    deviceAdapter.addDevice(savedDevice, "Watch Pro")
                    deviceAdapter.updateConnectionState(savedWatchMac, true)
                }
                if (savedCaseMac != null && CaseManager.isConnected.value == true) {
                    val savedDevice = bluetoothAdapter.getRemoteDevice(savedCaseMac)
                    deviceAdapter.addDevice(savedDevice, "Sentry Case")
                    deviceAdapter.updateConnectionState(savedCaseMac, true)
                }
            } catch (e: SecurityException) {
                Log.e("BLE", "Missing permission for getRemoteDevice")
            }
        }

        updateToggleState()

        switchBluetooth.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            if (isChecked && !bluetoothAdapter.isEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    isUpdatingSwitch = true
                    buttonView.isChecked = false
                    isUpdatingSwitch = false
                    requestTogglePermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                } else {
                    try {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } catch (e: Exception) {
                        Log.e("BLE", "Failed to launch Bluetooth enable intent", e)
                    }
                }
            } else if (!isChecked && bluetoothAdapter.isEnabled) {
                showSentryToast("Android requires you to disable this manually.", isLong = true)
                try {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (e: Exception) { e.printStackTrace() }

                isUpdatingSwitch = true
                buttonView.isChecked = true
                isUpdatingSwitch = false
            }
        }

        btnScan.setOnClickListener {
            if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
                showSentryToast("Please turn on Bluetooth first!", isLong = false)
                return@setOnClickListener
            }
            if (isScanning) {
                stopRadarScan()
                updateScanButtonUI(btnScan, false)
            } else {
                checkPermissionsAndScan()
                updateScanButtonUI(btnScan, true)
            }
        }
    }

    private fun showSetPinDialog() {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val currentPin = prefs.getString("watch_pairing_pin", "1234")

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_set_pin, null)
        val etPinCode = dialogView.findViewById<EditText>(R.id.etPinCode)
        val btnCancelPin = dialogView.findViewById<Button>(R.id.btnCancelPin)
        val btnSavePin = dialogView.findViewById<Button>(R.id.btnSavePin)

        etPinCode.setText(currentPin)

        dialogView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 64f
            setColor(Color.parseColor("#0F172A"))
            setStroke(2, Color.parseColor("#1E293B"))
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancelPin.setOnClickListener {
            dialog.dismiss()
        }

        btnSavePin.setOnClickListener {
            val newPin = etPinCode.text.toString()
            if (newPin.length in 4..6) {
                prefs.edit()
                    .putString("watch_pairing_pin", newPin)
                    .putStringSet("trusted_watches", mutableSetOf())
                    .apply()

                showSentryToast("PIN Saved! All watches must re-authenticate.", true)
                WatchManager.disconnect()
                dialog.dismiss()
            } else {
                showSentryToast("PIN must be 4 to 6 digits long.", false)
            }
        }

        dialog.show()
    }

    private fun showSentryToast(message: String, isLong: Boolean) {
        val toast = Toast(requireContext())
        toast.duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C"))
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            setPadding(50, 30, 50, 30)
        }

        val icon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_sentry_half_gold)
            layoutParams = LinearLayout.LayoutParams(60, 75).apply {
                setMargins(0, 0, 30, 0)
            }
        }

        val textView = TextView(requireContext()).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        customLayout.addView(icon)
        customLayout.addView(textView)
        toast.view = customLayout
        toast.show()
    }

    private fun updateScanButtonUI(button: Button, isScanning: Boolean) {
        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val btnBackground = GradientDrawable()
        btnBackground.shape = GradientDrawable.RECTANGLE
        btnBackground.cornerRadius = 1000f
        btnBackground.orientation = GradientDrawable.Orientation.TOP_BOTTOM

        if (isScanning) {
            if (isNightMode) {
                btnBackground.colors = intArrayOf(Color.parseColor("#3F000F"), Color.parseColor("#1A0004"))
                btnBackground.setStroke(4, Color.parseColor("#EF4444"))
                button.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                btnBackground.colors = intArrayOf(Color.parseColor("#FEF2F2"), Color.parseColor("#FEE2E2"))
                btnBackground.setStroke(4, Color.parseColor("#EF4444"))
                button.setTextColor(Color.parseColor("#7F1D1D"))
            }
            button.text = "STOP SCANNING"
        } else {
            if (isNightMode) {
                btnBackground.colors = intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#080E1A"))
                btnBackground.setStroke(4, Color.parseColor("#D4AF37"))
                button.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                btnBackground.colors = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F5F9"))
                btnBackground.setStroke(4, Color.parseColor("#2563EB"))
                button.setTextColor(Color.parseColor("#1E293B"))
            }
            button.text = "SCAN FOR DEVICES"
        }

        button.background = btnBackground
    }

    override fun onResume() {
        super.onResume()
        if (::bluetoothAdapter.isInitialized) updateToggleState()
        try {
            requireActivity().registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateToggleState() {
        val switchBluetooth = view?.findViewById<SwitchCompat>(R.id.switchBluetooth)
        switchBluetooth?.let {
            isUpdatingSwitch = true
            it.isChecked = bluetoothAdapter.isEnabled
            isUpdatingSwitch = false
        }
    }

    private fun checkPermissionsAndScan() {
        val reqPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val missing = reqPerms.filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRadarScan() else requestPermissionLauncher.launch(missing.toTypedArray())
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val scanRecordName = result.scanRecord?.deviceName ?: ""
                val deviceObjName = result.device.name ?: ""
                val deviceName = if (scanRecordName.isNotEmpty()) scanRecordName else deviceObjName

                val isTargetWatch = result.device.address.equals("FC:01:2C:FD:DD:76", ignoreCase = true) || deviceName.contains("Watch Pro", ignoreCase = true)

                val hasCaseUUID = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().equals("8101a153-61b6-444a-a0f5-502a5e958742", ignoreCase = true)
                } == true

                val isTargetCase = deviceName.contains("Sentry Case", ignoreCase = true) ||
                        deviceName.contains("ESP32", ignoreCase = true) ||
                        result.device.address == "80:F3:DA:63:43:0A" ||
                        hasCaseUUID

                if (isTargetWatch) {
                    deviceAdapter.addDevice(result.device, if (deviceName.isNotEmpty()) deviceName else "Watch Pro")
                } else if (isTargetCase) {
                    deviceAdapter.addDevice(result.device, "Sentry Case")
                }

            } catch (e: SecurityException) {
                Log.e("BLE", "Permission missing during scan", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRadarScan() {
        deviceAdapter.clear()
        isScanning = true
        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
        } catch (e: Exception) { e.printStackTrace() }

        Handler(Looper.getMainLooper()).postDelayed({
            if (isScanning) {
                stopRadarScan()
                view?.findViewById<Button>(R.id.btnScan)?.let { updateScanButtonUI(it, false) }
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun stopRadarScan() {
        isScanning = false
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) { e.printStackTrace() }
    }
}