package com.example.aisecurity.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
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

    // Variables to hold dialog state while pickers open
    private var tempPhotoUri: String = ""
    private var dialogAvatarImage: ImageView? = null
    private var dialogAvatarInitials: TextView? = null
    private var pendingEtName: EditText? = null
    private var pendingEtNumber: EditText? = null
    private var pendingTvCountryCode: TextView? = null

    // 1. THE IMAGE PICKER
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireActivity().contentResolver.takePersistableUriPermission(it, takeFlags)
                tempPhotoUri = it.toString()
                dialogAvatarImage?.setImageURI(it)
                dialogAvatarImage?.visibility = View.VISIBLE
                dialogAvatarInitials?.visibility = View.GONE
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. THE SMART PHONE BOOK PICKER
    private val pickPhoneLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)

            requireContext().contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val name = cursor.getString(nameIdx)
                    var number = cursor.getString(numberIdx)

                    // Clean the imported number from dashes or spaces
                    number = number.replace(Regex("[^0-9+]"), "")

                    // Smart Country Code Detection
                    if (number.startsWith("+63")) {
                        pendingTvCountryCode?.text = "+63 ▼"
                        number = "0" + number.removePrefix("+63")
                    } else if (number.startsWith("+1")) {
                        pendingTvCountryCode?.text = "+1 ▼"
                        number = number.removePrefix("+1")
                    } else if (number.startsWith("09") && number.length == 11) {
                        pendingTvCountryCode?.text = "+63 ▼"
                    }

                    pendingEtName?.setText(name)
                    pendingEtNumber?.setText(number)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_trusted_contacts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerContacts = view.findViewById<RecyclerView>(R.id.recyclerContacts)
        val btnAddContact = view.findViewById<Button>(R.id.btnAddContact)
        val btnBack = view.findViewById<TextView>(R.id.btnBack)

        btnBack.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyPrimaryButton(btnAddContact, isNightMode)

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

    // ==========================================
    // THE DIALOG SYSTEM & FORMATTING ENGINE
    // ==========================================
    private fun showAddEditDialog(existingContact: TrustedContact?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_edit_contact, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val etName = dialogView.findViewById<EditText>(R.id.etContactName)
        val etNumber = dialogView.findViewById<EditText>(R.id.etContactNumber)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnImportContact = dialogView.findViewById<TextView>(R.id.btnImportContact)
        val tvCountryCode = dialogView.findViewById<TextView>(R.id.tvCountryCode)

        dialogAvatarImage = dialogView.findViewById(R.id.imgDialogAvatar)
        dialogAvatarInitials = dialogView.findViewById(R.id.tvDialogInitials)
        val cardDialogAvatar = dialogView.findViewById<View>(R.id.cardDialogAvatar)

        // Bind global variables for the Address Book intent
        pendingEtName = etName
        pendingEtNumber = etNumber
        pendingTvCountryCode = tvCountryCode
        tempPhotoUri = ""

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // High-Opacity Glass Modal for Map Readability
        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor(Color.parseColor("#FA0F172A")) // 98% Opaque Navy
                setStroke(2, Color.parseColor("#334155"))
            } else {
                setColor(Color.parseColor("#FAFFFFFF")) // 98% Opaque White
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
        }
        dialogRoot.background = dialogBg

        applyPrimaryButton(btnSave, isNightMode)
        applyGhostButton(btnCancel, isNightMode)
        applyAvatarBackground(dialogAvatarInitials!!, isNightMode)

        // Default Input Filter to 11 digits for PH
        etNumber.filters = arrayOf(InputFilter.LengthFilter(11))

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // --- SMART EDIT PARSING ---
        if (existingContact != null) {
            tvTitle.text = "EDIT CONTACT"
            etName.setText(existingContact.name)

            // Deconstruct E.164 format back to Local Format for editing
            val number = existingContact.number
            if (number.startsWith("+")) {
                val parts = number.split(" ", limit = 2)
                if (parts.size == 2) {
                    val code = parts[0]
                    tvCountryCode.text = "$code ▼"
                    var localNum = parts[1].replace(" ", "")

                    // Re-inject the '0' for Filipino numbers so it looks natural
                    if (code == "+63" && !localNum.startsWith("0")) {
                        localNum = "0$localNum"
                    }
                    etNumber.setText(localNum)
                }
            } else {
                etNumber.setText(number)
            }

            tempPhotoUri = existingContact.photoUri
            if (tempPhotoUri.isNotEmpty()) {
                dialogAvatarImage?.setImageURI(Uri.parse(tempPhotoUri))
                dialogAvatarImage?.visibility = View.VISIBLE
                dialogAvatarInitials?.visibility = View.GONE
            } else {
                dialogAvatarInitials?.text = getInitials(existingContact.name)
            }
        }

        // COUNTRY CODE SELECTOR
        tvCountryCode.setOnClickListener {
            val countries = arrayOf("🇵🇭 +63 (PH)", "🇺🇸 +1 (US)", "🇬🇧 +44 (UK)", "🇦🇺 +61 (AU)", "🌐 Other")
            AlertDialog.Builder(requireContext())
                .setItems(countries) { _, which ->
                    val selected = countries[which]
                    val code = selected.split(" ")[1]
                    tvCountryCode.text = "$code ▼"

                    // Restrict Philippines to 11 Digits, others to 15 max
                    if (code == "+63") {
                        etNumber.filters = arrayOf(InputFilter.LengthFilter(11))
                    } else {
                        etNumber.filters = arrayOf(InputFilter.LengthFilter(15))
                    }
                }.show()
        }

        btnImportContact.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickPhoneLauncher.launch(intent)
        }

        cardDialogAvatar.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        // --- SMART SAVE FORMATTING ---
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val rawNumber = etNumber.text.toString().trim()
            val code = tvCountryCode.text.toString().replace(" ▼", "")

            if (name.isNotEmpty() && rawNumber.isNotEmpty()) {

                // Clean the number of any rogue spaces or characters
                var cleanNumber = rawNumber.replace(Regex("[^0-9]"), "")

                // Strip the leading '0' for international standardization if it's a PH number
                if (code == "+63" && cleanNumber.startsWith("0")) {
                    cleanNumber = cleanNumber.substring(1)
                }

                // Apply beautiful, readable formatting for the list
                val finalNumber = if (code == "+63" && cleanNumber.length == 10) {
                    "$code ${cleanNumber.substring(0,3)} ${cleanNumber.substring(3,6)} ${cleanNumber.substring(6)}" // +63 999 108 4986
                } else {
                    "$code $cleanNumber"
                }

                if (existingContact != null) {
                    existingContact.name = name
                    existingContact.number = finalNumber
                    existingContact.photoUri = tempPhotoUri
                } else {
                    contactsList.add(TrustedContact(UUID.randomUUID().toString(), name, finalNumber, tempPhotoUri))
                }
                saveContacts()
                adapter.notifyDataSetChanged()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "Please enter a Name and Number", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    // ==========================================
    // THE GLASSMORPHISM ENGINE
    // ==========================================
    private fun applyPrimaryButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#080E1A"))
            bg.setStroke(4, Color.parseColor("#D4AF37"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.colors = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F5F9"))
            bg.setStroke(4, Color.parseColor("#2563EB"))
            button.setTextColor(Color.parseColor("#1E293B"))
        }
        button.background = bg
    }

    private fun applyGhostButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        if (isNightMode) {
            bg.colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
            bg.setStroke(3, Color.parseColor("#334155"))
            button.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            bg.colors = intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"))
            bg.setStroke(3, Color.parseColor("#CBD5E1"))
            button.setTextColor(Color.parseColor("#64748B"))
        }
        button.background = bg
    }

    private fun applyAvatarBackground(textView: TextView, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#1E293B"))
            textView.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            bg.setColor(Color.parseColor("#E0E7FF"))
            textView.setTextColor(Color.parseColor("#1E3A8A"))
        }
        textView.background = bg
    }

    private fun getInitials(name: String): String {
        return name.trim().split("\\s+".toRegex())
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
    }

    // ==========================================
    // DATA MANAGEMENT
    // ==========================================
    private fun loadContacts() {
        contactsList.clear()
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("trusted_contacts_json", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = if (obj.has("id")) obj.getString("id") else UUID.randomUUID().toString()
                val photoUri = if (obj.has("photoUri")) obj.getString("photoUri") else ""
                contactsList.add(TrustedContact(id, obj.getString("name"), obj.getString("number"), photoUri))
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
                put("photoUri", contact.photoUri)
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

    // ==========================================
    // LIST ADAPTER
    // ==========================================
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

            val tvInitials: TextView = view.findViewById(R.id.tvContactInitials)
            val imgAvatar: ImageView = view.findViewById(R.id.imgContactAvatar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trusted_contact, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            val contact = contacts[position]
            holder.tvName.text = contact.name
            holder.tvNumber.text = contact.number

            val isNightMode = (holder.itemView.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            applyAvatarBackground(holder.tvInitials, isNightMode)

            if (contact.photoUri.isNotEmpty()) {
                try {
                    holder.imgAvatar.setImageURI(Uri.parse(contact.photoUri))
                    holder.imgAvatar.visibility = View.VISIBLE
                    holder.tvInitials.visibility = View.GONE
                } catch (e: Exception) {
                    holder.imgAvatar.visibility = View.GONE
                    holder.tvInitials.visibility = View.VISIBLE
                    holder.tvInitials.text = getInitials(contact.name)
                }
            } else {
                holder.imgAvatar.visibility = View.GONE
                holder.tvInitials.visibility = View.VISIBLE
                holder.tvInitials.text = getInitials(contact.name)
            }

            holder.tvEdit.setOnClickListener { onEditClick(contact) }
            holder.tvDelete.setOnClickListener { onDeleteClick(contact) }
        }

        override fun getItemCount() = contacts.size
    }
}