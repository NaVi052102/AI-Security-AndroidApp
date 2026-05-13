package com.example.aisecurity.ui.logs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.example.aisecurity.ai.SecurityDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsFragment : Fragment() {

    private lateinit var adapter: LogsAdapter
    private var realtimeJob: Job? = null // 🚨 NEW: Real-time sync job

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerLogs = view.findViewById<RecyclerView>(R.id.recyclerLogs)
        val btnDeleteSelected = view.findViewById<Button>(R.id.btnDeleteSelected)
        val btnClearAll = view.findViewById<Button>(R.id.btnClearAll)

        applyGlassmorphism(btnDeleteSelected, btnClearAll)

        adapter = LogsAdapter(emptyList())
        recyclerLogs.layoutManager = LinearLayoutManager(requireContext())
        recyclerLogs.adapter = adapter

        btnDeleteSelected.setOnClickListener {
            val idsToDelete = adapter.selectedIds.toList()
            if (idsToDelete.isEmpty()) {
                Toast.makeText(requireContext(), "No logs selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val db = SecurityDatabase.get(requireContext())
                db.securityLogDao().deleteLogsByIds(idsToDelete)

                withContext(Dispatchers.Main) {
                    adapter.clearSelections()
                    Toast.makeText(requireContext(), "Selected logs deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnClearAll.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = SecurityDatabase.get(requireContext())
                db.securityLogDao().clearAllLogs()

                withContext(Dispatchers.Main) {
                    adapter.clearSelections()
                    adapter.updateData(emptyList())
                    Toast.makeText(requireContext(), "All Logs Cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 🚨 NEW: Start polling the database every 2 seconds while the screen is open
    override fun onResume() {
        super.onResume()
        startRealtimeLogSync()
    }

    // 🚨 NEW: Stop polling when we leave the screen to save battery
    override fun onPause() {
        super.onPause()
        realtimeJob?.cancel()
    }

    private fun startRealtimeLogSync() {
        realtimeJob?.cancel()
        realtimeJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val db = SecurityDatabase.get(requireContext())
                    val logsFromDb = db.securityLogDao().getAllLogs()

                    val displayEvents = logsFromDb.map { log ->
                        SecurityEvent(
                            id = log.id,
                            timestamp = log.timestamp,
                            title = log.title,
                            details = log.details,
                            severity = log.severity
                        )
                    }

                    withContext(Dispatchers.Main) {
                        adapter.updateData(displayEvents)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(2000) // Refresh every 2 seconds
            }
        }
    }

    private fun applyGlassmorphism(btnDelete: Button, btnClear: Button) {
        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val deleteBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }

        val clearBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }

        if (isNightMode) {
            deleteBg.colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
            deleteBg.setStroke(3, Color.parseColor("#334155"))
            btnDelete.setTextColor(Color.parseColor("#94A3B8"))

            clearBg.colors = intArrayOf(Color.parseColor("#3F000F"), Color.parseColor("#1A0004"))
            clearBg.setStroke(4, Color.parseColor("#EF4444"))
            btnClear.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            deleteBg.colors = intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"))
            deleteBg.setStroke(3, Color.parseColor("#CBD5E1"))
            btnDelete.setTextColor(Color.parseColor("#64748B"))

            clearBg.colors = intArrayOf(Color.parseColor("#FEF2F2"), Color.parseColor("#FEE2E2"))
            clearBg.setStroke(4, Color.parseColor("#EF4444"))
            btnClear.setTextColor(Color.parseColor("#7F1D1D"))
        }

        btnDelete.background = deleteBg
        btnClear.background = clearBg
    }
}