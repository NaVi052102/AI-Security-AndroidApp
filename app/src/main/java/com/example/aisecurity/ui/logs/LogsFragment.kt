package com.example.aisecurity.ui.logs

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

                // IMPORTANT: Ensure you have a DAO method like:
                // @Query("DELETE FROM SecurityLogEntity WHERE id IN (:idList)")
                // fun deleteLogsByIds(idList: List<Int>)
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

    private fun loadLogsFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SecurityDatabase.get(requireContext())
            val logsFromDb = db.securityLogDao().getAllLogs()

            // Map the raw database logs into the UI events
            val displayEvents = logsFromDb.map { log ->
                SecurityEvent(
                    id = log.id, // Grab the actual database ID so we can delete it later
                    timestamp = log.timestamp,
                    title = log.title,
                    details = log.details,
                    severity = log.severity
                )
            }

            // Hop back to the Main UI thread to update the screen
            withContext(Dispatchers.Main) {
                adapter.updateData(displayEvents)
            }
        }
    }
}