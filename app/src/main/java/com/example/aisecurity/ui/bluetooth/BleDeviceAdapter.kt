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
    private val onDeviceClicked: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder>() {

    private val deviceList = mutableListOf<BluetoothDevice>()
    private var connectedDeviceMac: String? = null

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
        val device = deviceList[position]

        // Updated fallback name
        holder.tvName.text = device.name ?: "Watch Pro"
        holder.tvMac.text = device.address

        if (device.address == connectedDeviceMac) {
            holder.tvConnect?.text = "CONNECTED"
            holder.tvConnect?.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            holder.tvConnect?.text = "CONNECT"
            holder.tvConnect?.setTextColor(Color.parseColor("#2196F3"))
        }

        holder.itemView.setOnClickListener {
            connectedDeviceMac = device.address
            notifyDataSetChanged()
            onDeviceClicked(device)
        }
    }

    override fun getItemCount() = deviceList.size

    fun addDevice(device: BluetoothDevice) {
        if (!deviceList.any { it.address == device.address }) {
            deviceList.add(device)
            notifyItemInserted(deviceList.size - 1)
        }
    }

    fun setConnectedDevice(mac: String) {
        connectedDeviceMac = mac
        notifyDataSetChanged()
    }

    fun clear() {
        deviceList.clear()
        connectedDeviceMac = null
        notifyDataSetChanged()
    }
}