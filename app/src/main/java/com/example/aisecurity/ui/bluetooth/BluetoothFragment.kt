package com.example.aisecurity.ui.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ble.WatchManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class BluetoothFragment : Fragment() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceAdapter: BleDeviceAdapter
    private var isScanning = false

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateToggleState()
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) {
            startRadarScan()
        } else {
            Toast.makeText(requireContext(), "Permissions required to scan.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bluetooth, container, false)
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val switchBluetooth = view.findViewById<SwitchMaterial>(R.id.switchBluetooth)
        val btnScan = view.findViewById<MaterialButton>(R.id.btnScan)
        val recyclerDevices = view.findViewById<RecyclerView>(R.id.recyclerDevices)

        deviceAdapter = BleDeviceAdapter { clickedDevice ->
            prefs.edit().putString("saved_watch_mac", clickedDevice.address).apply()

            stopRadarScan()
            Toast.makeText(requireContext(), "Connecting to ${clickedDevice.name ?: "Watch Pro"}...", Toast.LENGTH_SHORT).show()
            WatchManager.connectToTarget(requireContext(), clickedDevice.address)
        }

        recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        recyclerDevices.adapter = deviceAdapter

        val savedMac = prefs.getString("saved_watch_mac", null)
        if (savedMac != null && bluetoothAdapter.isEnabled) {
            val savedDevice = bluetoothAdapter.getRemoteDevice(savedMac)
            deviceAdapter.addDevice(savedDevice)
            deviceAdapter.setConnectedDevice(savedMac)
        }

        updateToggleState()

        switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            } else if (!isChecked && bluetoothAdapter.isEnabled) {
                Toast.makeText(requireContext(), "Android requires you to disable this manually.", Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                switchBluetooth.isChecked = true
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
    }

    private fun updateToggleState() {
        view?.findViewById<SwitchMaterial>(R.id.switchBluetooth)?.isChecked = bluetoothAdapter.isEnabled
    }

    private fun checkPermissionsAndScan() {
        val reqPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missing = reqPerms.filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRadarScan() else requestPermissionLauncher.launch(missing.toTypedArray())
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name

            // THE MASTER KEY CHECK: Target by exact MAC Address or exact Name
            val isTargetWatch = result.device.address == "FC:01:2C:FD:DD:76" ||
                    (deviceName != null && deviceName.contains("Watch Pro", ignoreCase = true))

            if (isTargetWatch) {
                deviceAdapter.addDevice(result.device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRadarScan() {
        deviceAdapter.clear()

        val savedMac = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE).getString("saved_watch_mac", null)
        if (savedMac != null) {
            val savedDevice = bluetoothAdapter.getRemoteDevice(savedMac)
            deviceAdapter.addDevice(savedDevice)
            deviceAdapter.setConnectedDevice(savedMac)
        }

        isScanning = true
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)

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
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
    }
}