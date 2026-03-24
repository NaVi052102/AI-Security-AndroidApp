package com.example.aisecurity.ui.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.aisecurity.R

class HelpFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_help, container, false)

        val btnContactAdmin = view.findViewById<Button>(R.id.btnContactAdmin)

        btnContactAdmin.setOnClickListener {
            Toast.makeText(requireContext(), "Opening Admin Support Portal...", Toast.LENGTH_SHORT).show()
        }

        return view
    }
}