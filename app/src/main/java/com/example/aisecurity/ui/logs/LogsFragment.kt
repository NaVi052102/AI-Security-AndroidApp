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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsFragment : Fragment() {

    private lateinit var adapter: LogsAdapter

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

        // Inject the Light/Dark mode Glassmorphism designs
        applyGlassmorphism(btnDeleteSelected, btnClearAll)

        // 1. Initialize the adapter with an empty list
        adapter = LogsAdapter(emptyList())
        recyclerLogs.layoutManager = LinearLayoutManager(requireContext())
        recyclerLogs.adapter = adapter

        // 2. Fetch the REAL data from the AI Database
        loadLogsFromDatabase()

        // 3. DELETE SELECTED LOGS
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
                    adapter.clearSelections() // Uncheck everything
                    loadLogsFromDatabase()    // Refresh the screen
                    Toast.makeText(requireContext(), "Selected logs deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 4. CLEAR ALL LOGS
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

    // ==========================================
    // THE GLASSMORPHISM LOGS ENGINE
    // ==========================================
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
            // --- DARK MODE ---
            // Secondary/Ghost styling for Delete
            deleteBg.colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
            deleteBg.setStroke(3, Color.parseColor("#334155")) // Slate outline
            btnDelete.setTextColor(Color.parseColor("#94A3B8")) // Muted silver text

            // Destructive styling for Clear All
            clearBg.colors = intArrayOf(Color.parseColor("#3F000F"), Color.parseColor("#1A0004"))
            clearBg.setStroke(4, Color.parseColor("#EF4444")) // Crimson outline
            btnClear.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            // --- LIGHT MODE ---
            // Secondary/Ghost styling for Delete
            deleteBg.colors = intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"))
            deleteBg.setStroke(3, Color.parseColor("#CBD5E1")) // Light silver outline
            btnDelete.setTextColor(Color.parseColor("#64748B")) // Muted dark text

            // Destructive styling for Clear All
            clearBg.colors = intArrayOf(Color.parseColor("#FEF2F2"), Color.parseColor("#FEE2E2"))
            clearBg.setStroke(4, Color.parseColor("#EF4444")) // Crisp Red outline
            btnClear.setTextColor(Color.parseColor("#7F1D1D")) // Deep Red text
        }

        btnDelete.background = deleteBg
        btnClear.background = clearBg
    }

    private fun loadLogsFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
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
        }
    }
}