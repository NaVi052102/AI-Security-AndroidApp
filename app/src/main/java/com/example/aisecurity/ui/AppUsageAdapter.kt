package com.example.aisecurity.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ai.AppUsageProfile

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.UsageViewHolder>() {

    private var dataList = listOf<AppUsageProfile>()
    private var confidences: Map<String, Int> = emptyMap()

    fun updateData(newList: List<AppUsageProfile>, newConfidences: Map<String, Int> = emptyMap()) {
        this.dataList = newList
        this.confidences = newConfidences
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return UsageViewHolder(view)
    }

    override fun onBindViewHolder(holder: UsageViewHolder, position: Int) {
        val item = dataList[position]

        holder.tvName.text = item.packageName
        holder.tvSpeed.text = "${item.avgVelocity.toInt()} px/s"
        holder.tvCount.text = "${item.interactionCount}"

        val percentage = confidences[item.packageName] ?: 0

        holder.pbAppTraining.progress = percentage

        // 🚨 FIX: Changed the else block to use Color.WHITE so it is always readable
        if (percentage >= 100) {
            holder.tvProgress.text = "ARMED 🛡️"
            holder.tvProgress.setTextColor(Color.WHITE)
        } else {
            holder.tvProgress.text = "$percentage%"
            holder.tvProgress.setTextColor(Color.WHITE)
        }
    }

    override fun getItemCount() = dataList.size

    class UsageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvSpeed: TextView = itemView.findViewById(R.id.tvAppSpeed)
        val tvCount: TextView = itemView.findViewById(R.id.tvAppCount)

        val tvProgress: TextView = itemView.findViewById(R.id.tvAppProgress)
        val pbAppTraining: ProgressBar = itemView.findViewById(R.id.pbAppTraining)
    }
}