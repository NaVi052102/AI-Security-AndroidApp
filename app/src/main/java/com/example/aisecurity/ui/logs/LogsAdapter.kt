package com.example.aisecurity.ui.logs

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R

// Added 'id' so we know which specific database row to delete!
data class SecurityEvent(
    val id: Int,
    val timestamp: String,
    val title: String,
    val details: String,
    val severity: Int // 0 = Green, 1 = Orange, 2 = Red
)

class LogsAdapter(private var logsList: List<SecurityEvent>) :
    RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    // Tracks the IDs of the logs you have checked
    val selectedIds = mutableSetOf<Int>()

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // 🚨 FIXED: These IDs now perfectly match your new premium item_log.xml!
        val indicator: View = view.findViewById(R.id.indicatorLine)
        val tvTime: TextView = view.findViewById(R.id.tvLogTimestamp)
        val tvTitle: TextView = view.findViewById(R.id.tvLogTitle)
        val tvContext: TextView = view.findViewById(R.id.tvLogDetails)
        val cbSelect: CheckBox = view.findViewById(R.id.cbLogSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logsList[position]

        // Push the data to the correct text views
        holder.tvTime.text = log.timestamp
        holder.tvTitle.text = log.title
        holder.tvContext.text = log.details

        // Map severity to colors
        when (log.severity) {
            0 -> holder.indicator.setBackgroundColor(Color.parseColor("#10B981")) // Emerald Green
            1 -> holder.indicator.setBackgroundColor(Color.parseColor("#F59E0B")) // Warning Orange
            2 -> holder.indicator.setBackgroundColor(Color.parseColor("#EF4444")) // Threat Red
        }

        // --- CHECKBOX LOGIC ---
        // Detach listener temporarily so it doesn't glitch when scrolling
        holder.cbSelect.setOnCheckedChangeListener(null)

        // Check the box if we memorized it
        holder.cbSelect.isChecked = selectedIds.contains(log.id)

        // Listen for user clicks
        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedIds.add(log.id)
            } else {
                selectedIds.remove(log.id)
            }
        }
    }

    override fun getItemCount() = logsList.size

    fun updateData(newLogs: List<SecurityEvent>) {
        logsList = newLogs
        notifyDataSetChanged()
    }

    fun clearSelections() {
        selectedIds.clear()
        notifyDataSetChanged()
    }
}