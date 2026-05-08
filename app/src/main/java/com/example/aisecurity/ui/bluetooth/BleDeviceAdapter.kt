package com.example.aisecurity.ui.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R

class BleDeviceAdapter(
    // 🚨 FIX: We now pass the reliable resolved name into the click listener
    private val onDeviceClicked: (BluetoothDevice, String, Boolean) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder>() {

    private val deviceList = mutableListOf<Pair<BluetoothDevice, String>>()
    private val connectedMacs = mutableSetOf<String>()

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvMac: TextView = view.findViewById(R.id.tvDeviceMac)
        val tvConnect: TextView? = view.findViewById(R.id.tvConnect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ble_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val (device, discoveredName) = deviceList[position]

        // 🚨 BULLETPROOF NAME RESOLUTION
        val finalName = when {
            device.address.equals("FC:01:2C:FD:DD:76", ignoreCase = true) -> "Watch Pro"
            discoveredName.isNotEmpty() -> discoveredName
            device.name != null -> device.name
            else -> "Unknown Device"
        }

        holder.tvName.text = finalName
        holder.tvMac.text = device.address

        val isConnected = connectedMacs.contains(device.address)

        if (isConnected) {
            holder.tvConnect?.text = "DISCONNECT"
            holder.tvConnect?.setTextColor(Color.parseColor("#F44336"))
        } else {
            holder.tvConnect?.text = "CONNECT"
            holder.tvConnect?.setTextColor(Color.parseColor("#2196F3"))
        }

        holder.itemView.setOnClickListener {
            // 🚨 Pass the bulletproof finalName back to the Fragment
            onDeviceClicked(device, finalName, isConnected)
        }
    }

    override fun getItemCount() = deviceList.size

    fun addDevice(device: BluetoothDevice, discoveredName: String) {
        if (!deviceList.any { it.first.address == device.address }) {
            deviceList.add(Pair(device, discoveredName))
            notifyItemInserted(deviceList.size - 1)
        }
    }

    fun updateConnectionState(mac: String, isConnected: Boolean) {
        if (isConnected) {
            connectedMacs.add(mac)
        } else {
            connectedMacs.remove(mac)
        }
        notifyDataSetChanged()
    }

    fun clear() {
        deviceList.clear()
        connectedMacs.clear()
        notifyDataSetChanged()
    }
}