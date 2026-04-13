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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager
import com.google.android.material.button.MaterialButton

class BluetoothFragment : Fragment() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceAdapter: BleDeviceAdapter
    private var isScanning = false

    // 🚨 FIX: This lock prevents the switch from crashing in an infinite loop
    private var isUpdatingSwitch = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

                if (state == BluetoothAdapter.STATE_OFF) {
                    updateToggleState()
                    deviceAdapter.clear()

                    if (isScanning) {
                        isScanning = false
                        view?.findViewById<MaterialButton>(R.id.btnScan)?.apply {
                            text = "START RADAR SCAN"
                            setBackgroundColor(Color.parseColor("#2196F3"))
                        }
                    }
                } else if (state == BluetoothAdapter.STATE_ON) {
                    updateToggleState()
                }
            }
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateToggleState()
    }

    // 🚨 FIX: New Permission Launcher dedicated specifically to the Toggle Switch!
    private val requestTogglePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            try {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) { Log.e("BLE", "Error enabling Bluetooth", e) }
        } else {
            Toast.makeText(requireContext(), "Permission needed to enable Bluetooth.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) startRadarScan()
        else Toast.makeText(requireContext(), "Permissions required to scan.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "Please ALLOW Notification Access for Music Sync!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val switchBluetooth = view.findViewById<SwitchCompat>(R.id.switchBluetooth)
        val btnScan = view.findViewById<MaterialButton>(R.id.btnScan)
        val recyclerDevices = view.findViewById<RecyclerView>(R.id.recyclerDevices)

        deviceAdapter = BleDeviceAdapter { clickedDevice, isAlreadyConnected ->
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(requireContext(), "Please turn on Bluetooth first!", Toast.LENGTH_SHORT).show()
                return@BleDeviceAdapter
            }

            if (isAlreadyConnected) {
                WatchManager.disconnect()
                deviceAdapter.setConnectedDevice(null)
                Toast.makeText(requireContext(), "Watch Disconnected", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("saved_watch_mac", clickedDevice.address).apply()
                stopRadarScan()
                deviceAdapter.setConnectedDevice(clickedDevice.address)

                try {
                    Toast.makeText(requireContext(), "Connecting to ${clickedDevice.name ?: "Watch Pro"}...", Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Toast.makeText(requireContext(), "Connecting to Watch Pro...", Toast.LENGTH_SHORT).show()
                }
                WatchManager.connectToTarget(requireContext(), clickedDevice.address)
            }
        }

        recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        recyclerDevices.adapter = deviceAdapter

        WatchManager.isConnected.observe(viewLifecycleOwner) { isConnected ->
            if (!isConnected) {
                deviceAdapter.setConnectedDevice(null)
            }
        }

        val savedMac = prefs.getString("saved_watch_mac", null)
        if (savedMac != null && bluetoothAdapter.isEnabled && WatchManager.isConnected.value == true) {
            try {
                val savedDevice = bluetoothAdapter.getRemoteDevice(savedMac)
                deviceAdapter.addDevice(savedDevice)
                deviceAdapter.setConnectedDevice(savedMac)
            } catch (e: SecurityException) {
                Log.e("BLE", "Missing permission for getRemoteDevice")
            }
        }

        updateToggleState()

        switchBluetooth.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener // Prevents UI looping

            if (isChecked && !bluetoothAdapter.isEnabled) {
                // 🚨 THE CRASH FIX: Ask for permission securely before enabling Bluetooth!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    isUpdatingSwitch = true
                    buttonView.isChecked = false // Bounce back visually until permission is granted
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
                Toast.makeText(requireContext(), "Android requires you to disable this manually.", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (e: Exception) { e.printStackTrace() }

                isUpdatingSwitch = true
                buttonView.isChecked = true // Keep it checked because they must disable via settings
                isUpdatingSwitch = false
            }
        }

        btnScan.setOnClickListener {
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(requireContext(), "Please turn on Bluetooth first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isScanning) {
                stopRadarScan()
                btnScan.text = "START RADAR SCAN"
                btnScan.setBackgroundColor(Color.parseColor("#2196F3"))
            } else {
                checkPermissionsAndScan()
                btnScan.text = "STOP SCANNING"
                btnScan.setBackgroundColor(Color.parseColor("#F44336"))
            }
        }
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
                val deviceName = result.device.name
                val isTargetWatch = result.device.address == "FC:01:2C:FD:DD:76" || (deviceName != null && deviceName.contains("Watch Pro", true))
                if (isTargetWatch) deviceAdapter.addDevice(result.device)
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
                view?.findViewById<MaterialButton>(R.id.btnScan)?.let {
                    it.text = "START RADAR SCAN"
                    it.setBackgroundColor(Color.parseColor("#2196F3"))
                }
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