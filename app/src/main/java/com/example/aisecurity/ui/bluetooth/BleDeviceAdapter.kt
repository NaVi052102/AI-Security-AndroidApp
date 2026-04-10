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
    // FIXED: Now accepts TWO parameters so your teammate's disconnect logic works!
    private val onDeviceClicked: (BluetoothDevice, Boolean) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder>() {

    private val deviceList = mutableListOf<BluetoothDevice>()
    private var connectedDeviceMac: String? = null

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvMac: TextView = view.findViewById(R.id.tvDeviceMac)
        // FIXED: Matched to the new XML ID
        val tvConnectionStatus: TextView = view.findViewById(R.id.tvConnectionStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ble_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = deviceList[position]

        holder.tvName.text = device.name ?: "Watch Pro"
        holder.tvMac.text = device.address

        val isConnected = device.address == connectedDeviceMac

        // FIXED: Now uses the clean visibility logic for the premium UI
        if (isConnected) {
            holder.tvConnectionStatus.visibility = View.VISIBLE
            holder.tvConnectionStatus.text = "CONNECTED"
            holder.tvConnectionStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            holder.tvConnectionStatus.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            // FIXED: Passes both the device AND whether it's already connected back to the fragment
            onDeviceClicked(device, isConnected)
        }
    }

    override fun getItemCount() = deviceList.size

    fun addDevice(device: BluetoothDevice) {
        if (!deviceList.any { it.address == device.address }) {
            deviceList.add(device)
            notifyItemInserted(deviceList.size - 1)
        }
    }

    // FIXED: Allowed 'mac' to be null so we can cleanly disconnect
    fun setConnectedDevice(mac: String?) {
        connectedDeviceMac = mac
        notifyDataSetChanged()
    }

    fun clear() {
        deviceList.clear()
        connectedDeviceMac = null
        notifyDataSetChanged()
    }
}