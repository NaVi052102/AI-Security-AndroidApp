package com.example.aisecurity.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ai.AppUsageProfile

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.UsageViewHolder>() {

    private var dataList = listOf<AppUsageProfile>()

    fun updateData(newData: List<AppUsageProfile>) {
        dataList = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return UsageViewHolder(view)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val item = dataList[position]

        // AppMonitor now provides the exact formatted name, so we just pass it straight to the UI
        holder.tvName.text = item.packageName

        holder.tvSpeed.text = "${item.avgVelocity.toInt()} px/s"
        holder.tvCount.text = "${item.interactionCount}"
    }

    override fun getItemCount() = dataList.size

    class UsageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvSpeed: TextView = itemView.findViewById(R.id.tvAppSpeed)
        val tvCount: TextView = itemView.findViewById(R.id.tvAppCount)
    }
}