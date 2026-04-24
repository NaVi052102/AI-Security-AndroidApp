package com.example.aisecurity.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ai.AppUsageProfile

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.UsageViewHolder>() {

    private var dataList = listOf<AppUsageProfile>()
    private var confidences: Map<String, Int> = emptyMap()

    // 🚨 RAM CACHE: Prevents the RecyclerView from lagging when scrolling
    private val iconCache = mutableMapOf<String, Drawable?>()

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

        // Load Icon safely
        val icon = fetchAppIcon(holder.itemView.context, item.packageName)
        if (icon != null) {
            holder.ivAppIcon.setImageDrawable(icon)
            holder.ivAppIcon.visibility = View.VISIBLE
        } else {
            holder.ivAppIcon.visibility = View.GONE
        }

        val percentage = confidences[item.packageName] ?: 0

        holder.pbAppTraining.progress = percentage

        if (percentage >= 100) {
            holder.tvProgress.text = "ARMED 🛡️"
            holder.tvProgress.setTextColor(Color.WHITE)
        } else {
            holder.tvProgress.text = "$percentage%"
            holder.tvProgress.setTextColor(Color.WHITE)
        }
    }

    override fun getItemCount() = dataList.size

    // 🚨 High-Speed OS Icon Fetcher
    private fun fetchAppIcon(context: Context, appName: String): Drawable? {
        if (iconCache.containsKey(appName)) return iconCache[appName]

        val pm = context.packageManager
        val knownPackages = mapOf(
            "Facebook" to "com.facebook.katana",
            "Messenger" to "com.facebook.orca",
            "TikTok" to "com.zhiliaoapp.musically",
            "Instagram" to "com.instagram.android",
            "YouTube" to "com.google.android.youtube",
            "WhatsApp" to "com.whatsapp",
            "X (Twitter)" to "com.twitter.android",
            "Mobile Legends" to "com.mobile.legends",
            "System UI" to "com.android.systemui",
            "Home Screen" to "com.miui.home"
        )

        var icon: Drawable? = null
        if (knownPackages.containsKey(appName)) {
            try { icon = pm.getApplicationIcon(knownPackages[appName]!!) } catch (e: Exception) {}
        } else {
            try {
                val packages = pm.getInstalledApplications(0)
                for (appInfo in packages) {
                    if (pm.getApplicationLabel(appInfo).toString().equals(appName, ignoreCase = true)) {
                        icon = pm.getApplicationIcon(appInfo)
                        break
                    }
                }
            } catch (e: Exception) {}
        }

        if (icon == null) {
            try { icon = ContextCompat.getDrawable(context, android.R.mipmap.sym_def_app_icon) } catch (e: Exception) {}
        }

        iconCache[appName] = icon
        return icon
    }

    class UsageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvSpeed: TextView = itemView.findViewById(R.id.tvAppSpeed)
        val tvCount: TextView = itemView.findViewById(R.id.tvAppCount)
        val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon) // 🚨 Bound new icon
        val tvProgress: TextView = itemView.findViewById(R.id.tvAppProgress)
        val pbAppTraining: ProgressBar = itemView.findViewById(R.id.pbAppTraining)
    }
}