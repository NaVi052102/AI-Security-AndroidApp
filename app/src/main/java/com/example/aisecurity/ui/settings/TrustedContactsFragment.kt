package com.example.aisecurity.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TrustedContact(
    val id: String,
    var name: String,
    var number: String,
    var photoUri: String = ""
)

class TrustedContactsFragment : Fragment() {

    private lateinit var adapter: ContactsAdapter
    private val contactsList = mutableListOf<TrustedContact>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_trusted_contacts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerContacts = view.findViewById<RecyclerView>(R.id.recyclerContacts)
        val btnAddContact = view.findViewById<MaterialButton>(R.id.btnAddContact)

        // --- FIXED: Updated to TextView to match the new straight arrow XML ---
        val btnBack = view.findViewById<TextView>(R.id.btnBack)

        // --- FIXED: Safely pop the back stack ---
        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        adapter = ContactsAdapter(
            contacts = contactsList,
            onEditClick = { contact -> showAddEditDialog(contact) },
            onDeleteClick = { contact -> removeContact(contact) }
        )
        recyclerContacts.layoutManager = LinearLayoutManager(requireContext())
        recyclerContacts.adapter = adapter

        loadContacts()
        btnAddContact.setOnClickListener { showAddEditDialog(null) }
    }

    private fun showAddEditDialog(existingContact: TrustedContact?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_edit_contact, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val etName = dialogView.findViewById<EditText>(R.id.etContactName)
        val etNumber = dialogView.findViewById<EditText>(R.id.etContactNumber)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (existingContact != null) {
            tvTitle.text = "EDIT COMMANDER"
            etName.setText(existingContact.name)
            etNumber.setText(existingContact.number)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val number = etNumber.text.toString().trim()

            if (name.isNotEmpty() && number.isNotEmpty()) {
                if (existingContact != null) {
                    existingContact.name = name
                    existingContact.number = number
                } else {
                    contactsList.add(TrustedContact(UUID.randomUUID().toString(), name, number))
                }
                saveContacts()
                adapter.notifyDataSetChanged()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "Incomplete details", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun loadContacts() {
        contactsList.clear()
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("trusted_contacts_json", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = if (obj.has("id")) obj.getString("id") else UUID.randomUUID().toString()
                contactsList.add(TrustedContact(id, obj.getString("name"), obj.getString("number")))
            }
            adapter.notifyDataSetChanged()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveContacts() {
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (contact in contactsList) {
            val obj = JSONObject().apply {
                put("id", contact.id)
                put("name", contact.name)
                put("number", contact.number)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("trusted_contacts_json", jsonArray.toString()).apply()
    }

    private fun removeContact(contact: TrustedContact) {
        contactsList.remove(contact)
        saveContacts()
        adapter.notifyDataSetChanged()
    }

    inner class ContactsAdapter(
        private val contacts: List<TrustedContact>,
        private val onEditClick: (TrustedContact) -> Unit,
        private val onDeleteClick: (TrustedContact) -> Unit
    ) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

        inner class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvContactName)
            val tvNumber: TextView = view.findViewById(R.id.tvContactNumber)
            val tvEdit: TextView = view.findViewById(R.id.tvEditContact)
            val tvDelete: TextView = view.findViewById(R.id.tvDeleteContact)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trusted_contact, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            val contact = contacts[position]
            holder.tvName.text = contact.name
            holder.tvNumber.text = contact.number
            holder.tvEdit.setOnClickListener { onEditClick(contact) }
            holder.tvDelete.setOnClickListener { onDeleteClick(contact) }
        }

        override fun getItemCount() = contacts.size
    }
}