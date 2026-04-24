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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// 🚨 DATA MODEL UPDATED: Added a status field to track mutual consent
data class TrustedContact(
    val id: String,
    var name: String,
    val number: String, // Made val so it can't be changed after creation
    var photoUri: String = "",
    var uid: String = "",
    var status: String = "ACCEPTED" // PENDING, ACCEPTED, or SMS_ONLY
)

class TrustedContactsFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var adapter: ContactsAdapter
    private val contactsList = mutableListOf<TrustedContact>()

    private var pendingEtName: EditText? = null
    private var pendingEtNumber: EditText? = null
    private var pendingTvCountryCode: TextView? = null

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

                    number = number.replace(Regex("[^0-9+]"), "")

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

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val recyclerContacts = view.findViewById<RecyclerView>(R.id.recyclerContacts)
        val btnAddContact = view.findViewById<Button>(R.id.btnAddContact)
        val btnBack = view.findViewById<TextView>(R.id.btnBack)

        btnBack.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyPrimaryButton(btnAddContact, isNightMode)

        adapter = ContactsAdapter(
            contacts = contactsList,
            onEditClick = { contact -> showAddEditDialog(contact) },
            onDeleteClick = { contact -> showDeleteConfirmation(contact, isNightMode) } // 🚨 Updated to show confirmation
        )
        recyclerContacts.layoutManager = LinearLayoutManager(requireContext())
        recyclerContacts.adapter = adapter

        loadContacts()
        btnAddContact.setOnClickListener { showAddEditDialog(null) }
    }

    // ==========================================
    // 🚨 NEW: DELETE CONFIRMATION DIALOG
    // ==========================================
    private fun showDeleteConfirmation(contact: TrustedContact, isNightMode: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_secure_verification, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvVerificationTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvVerificationMessage)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnProceed)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        tvTitle.text = "Remove Contact"
        tvMessage.text = "Are you sure you want to remove ${contact.name}? They will lose access to your location and emergency alerts."

        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor(Color.parseColor("#FA0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            } else {
                setColor(Color.parseColor("#FAFFFFFF"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
        }
        dialogRoot.background = dialogBg

        // Destructive button styling
        val proceedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
            if (isNightMode) {
                setColor(Color.parseColor("#EF4444"))
            } else {
                setColor(Color.parseColor("#DC2626"))
            }
        }
        btnProceed.background = proceedBg
        btnProceed.text = "REMOVE"

        applyGhostButton(btnCancel, isNightMode)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnProceed.setOnClickListener {
            dialog.dismiss()
            removeContact(contact)
        }
        dialog.show()
    }

    private fun toggleFullScreen(isFullScreen: Boolean) {
        val activity = activity ?: return
        val appBar = activity.findViewById<View>(R.id.appBarLayout)
        val bottomNav = activity.findViewById<View>(R.id.bottom_navigation_container)
        val container = activity.findViewById<View>(R.id.fragment_container)

        if (appBar == null || bottomNav == null || container == null) return

        val params = container.layoutParams as ConstraintLayout.LayoutParams

        if (isFullScreen) {
            appBar.visibility = View.GONE
            bottomNav.visibility = View.GONE

            params.topToBottom = ConstraintLayout.LayoutParams.UNSET
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID

            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            appBar.visibility = View.VISIBLE
            bottomNav.visibility = View.VISIBLE

            params.topToTop = ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.appBarLayout

            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.bottomToTop = R.id.bottom_navigation_container
        }

        container.layoutParams = params
        container.requestLayout()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) toggleFullScreen(true)
    }

    override fun onPause() {
        super.onPause()
        toggleFullScreen(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        toggleFullScreen(false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        toggleFullScreen(!hidden)
    }

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

        pendingEtName = etName
        pendingEtNumber = etNumber
        pendingTvCountryCode = tvCountryCode

        val isNightMode = (requireContext().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialogBg = GradientDrawable().apply {
            cornerRadius = 60f
            if (isNightMode) {
                setColor(Color.parseColor("#FA0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            } else {
                setColor(Color.parseColor("#FAFFFFFF"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
        }
        dialogRoot.background = dialogBg

        applyPrimaryButton(btnSave, isNightMode)
        applyGhostButton(btnCancel, isNightMode)

        etNumber.filters = arrayOf(InputFilter.LengthFilter(11))

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (existingContact != null) {
            tvTitle.text = "EDIT CONTACT"
            etName.setText(existingContact.name)

            // 🚨 LOCK THE PHONE NUMBER FIELD SO IT CANT BE EDITED
            etNumber.isEnabled = false
            etNumber.alpha = 0.5f
            tvCountryCode.isEnabled = false
            tvCountryCode.alpha = 0.5f

            val number = existingContact.number
            if (number.startsWith("+")) {
                val parts = number.split(" ", limit = 2)
                if (parts.size == 2) {
                    val code = parts[0]
                    tvCountryCode.text = "$code ▼"
                    var localNum = parts[1].replace(" ", "")

                    if (code == "+63" && !localNum.startsWith("0")) {
                        localNum = "0$localNum"
                    }
                    etNumber.setText(localNum)
                }
            } else {
                etNumber.setText(number)
            }
        }

        tvCountryCode.setOnClickListener {
            val countries = arrayOf("🇵🇭 +63 (PH)", "🇺🇸 +1 (US)", "🇬🇧 +44 (UK)", "🇦🇺 +61 (AU)", "🌐 Other")
            AlertDialog.Builder(requireContext())
                .setItems(countries) { _, which ->
                    val selected = countries[which]
                    val code = selected.split(" ")[1]
                    tvCountryCode.text = "$code ▼"

                    if (code == "+63") etNumber.filters = arrayOf(InputFilter.LengthFilter(11))
                    else etNumber.filters = arrayOf(InputFilter.LengthFilter(15))
                }.show()
        }

        btnImportContact.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            pickPhoneLauncher.launch(intent)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val rawNumber = etNumber.text.toString().trim()
            val code = tvCountryCode.text.toString().replace(" ▼", "")

            if (name.isNotEmpty() && rawNumber.isNotEmpty()) {
                btnSave.isEnabled = false
                btnSave.text = "VERIFYING..."

                // 🚨 If Editing, we skip database verification and just update the name locally.
                if (existingContact != null) {
                    existingContact.name = name
                    saveContacts()
                    adapter.notifyDataSetChanged()
                    dialog.dismiss()
                    return@setOnClickListener
                }

                // 🚨 Otherwise, it's a NEW ADDITION: Proceed with full validation
                var cleanNumber = rawNumber.replace(Regex("[^0-9]"), "")
                if (code == "+63" && cleanNumber.startsWith("0")) cleanNumber = cleanNumber.substring(1)

                val searchNumber = "$code$cleanNumber"

                val uiFormattedNumber = if (code == "+63" && cleanNumber.length == 10) {
                    "$code ${cleanNumber.substring(0,3)} ${cleanNumber.substring(3,6)} ${cleanNumber.substring(6)}"
                } else {
                    "$code $cleanNumber"
                }

                db.collection("Users").whereEqualTo("phoneNumber", searchNumber).get()
                    .addOnSuccessListener { documents ->
                        var linkedUid = ""
                        var fetchedPhotoUri = ""
                        var contactStatus = "SMS_ONLY"

                        if (!documents.isEmpty) {
                            val userDoc = documents.documents[0]
                            linkedUid = userDoc.id
                            contactStatus = "PENDING" // 🚨 Set status to pending instead of forcing it

                            val remotePhoto = userDoc.getString("photoUri") ?: ""
                            if (remotePhoto.isNotEmpty()) fetchedPhotoUri = remotePhoto

                            showSentryToast("Waiting for $name to accept.", isLong = true)

                            // 🚨 PUSH THE INVITE TO THE TARGET USER
                            val myUid = auth.currentUser?.uid
                            if (myUid != null && linkedUid.isNotEmpty() && myUid != linkedUid) {
                                db.collection("Users").document(linkedUid).get().addOnSuccessListener { theirDoc ->
                                    val theirContacts = theirDoc.get("trustedContacts") as? MutableList<Map<String, String>> ?: mutableListOf()

                                    // If we aren't in their list at all, send an invite
                                    if (theirContacts.none { it["uid"] == myUid }) {
                                        db.collection("Users").document(myUid).get().addOnSuccessListener { myDoc ->
                                            val myName = myDoc.getString("fullName") ?: myDoc.getString("name") ?: "Sentry User"
                                            val myPhone = myDoc.getString("phoneNumber") ?: myDoc.getString("number") ?: ""
                                            val myPhoto = myDoc.getString("photoUri") ?: ""

                                            val myContactForThem = mapOf(
                                                "id" to UUID.randomUUID().toString(),
                                                "name" to myName,
                                                "number" to myPhone,
                                                "photoUri" to myPhoto,
                                                "uid" to myUid,
                                                "status" to "PENDING_RECEIVED" // Tag so their UI knows it's an inbound request
                                            )

                                            theirContacts.add(myContactForThem)
                                            db.collection("Users").document(linkedUid).set(mapOf("trustedContacts" to theirContacts), SetOptions.merge())
                                        }
                                    }
                                }
                            }
                        } else {
                            showSentryToast("Not on app. Saved as SMS-Only.", isLong = true)
                        }

                        // Add to our local list
                        contactsList.add(TrustedContact(UUID.randomUUID().toString(), name, uiFormattedNumber, fetchedPhotoUri, linkedUid, contactStatus))

                        saveContacts()
                        adapter.notifyDataSetChanged()
                        dialog.dismiss()
                    }
                    .addOnFailureListener {
                        showSentryToast("Network Error. Try again.", isLong = false)
                        btnSave.isEnabled = true
                        btnSave.text = "SAVE"
                    }

            } else {
                showSentryToast("Please enter a Name and Number", isLong = false)
            }
        }
        dialog.show()
    }

    private fun applyPrimaryButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
        }
        if (isNightMode) {
            bg.setColor(Color.parseColor("#3B82F6"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            bg.setColor(Color.parseColor("#2563EB"))
            button.setTextColor(Color.parseColor("#FFFFFF"))
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
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
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
                val uid = if (obj.has("uid")) obj.getString("uid") else ""
                val status = if (obj.has("status")) obj.getString("status") else "ACCEPTED"
                contactsList.add(TrustedContact(id, obj.getString("name"), obj.getString("number"), photoUri, uid, status))
            }
            adapter.notifyDataSetChanged()
        } catch (e: Exception) { e.printStackTrace() }

        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("Users").document(userId).get().addOnSuccessListener { doc ->
                if (doc != null && doc.contains("trustedContacts")) {
                    try {
                        val cloudContacts = doc.get("trustedContacts") as? List<Map<String, String>>
                        if (cloudContacts != null && cloudContacts.isNotEmpty()) {
                            contactsList.clear()
                            val updatedJsonArray = JSONArray()

                            for (c in cloudContacts) {
                                val id = c["id"] ?: UUID.randomUUID().toString()
                                val name = c["name"] ?: ""
                                val number = c["number"] ?: ""
                                val photoUri = c["photoUri"] ?: ""
                                val uid = c["uid"] ?: ""
                                val status = c["status"] ?: "ACCEPTED"

                                contactsList.add(TrustedContact(id, name, number, photoUri, uid, status))

                                val obj = JSONObject().apply {
                                    put("id", id)
                                    put("name", name)
                                    put("number", number)
                                    put("photoUri", photoUri)
                                    put("uid", uid)
                                    put("status", status)
                                }
                                updatedJsonArray.put(obj)
                            }
                            prefs.edit().putString("trusted_contacts_json", updatedJsonArray.toString()).apply()
                            adapter.notifyDataSetChanged()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }

    private fun saveContacts() {
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        val firestoreList = mutableListOf<Map<String, String>>()

        for (contact in contactsList) {
            val obj = JSONObject().apply {
                put("id", contact.id)
                put("name", contact.name)
                put("number", contact.number)
                put("photoUri", contact.photoUri)
                put("uid", contact.uid)
                put("status", contact.status)
            }
            jsonArray.put(obj)

            firestoreList.add(mapOf(
                "id" to contact.id,
                "name" to contact.name,
                "number" to contact.number,
                "photoUri" to contact.photoUri,
                "uid" to contact.uid,
                "status" to contact.status
            ))
        }

        prefs.edit().putString("trusted_contacts_json", jsonArray.toString()).apply()

        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("Users").document(userId)
                .set(mapOf("trustedContacts" to firestoreList), SetOptions.merge())
        }
    }

    private fun removeContact(contact: TrustedContact) {
        contactsList.remove(contact)
        saveContacts()
        adapter.notifyDataSetChanged()
    }

    // ==========================================
    // 🚨 ADAPTER UPDATED: Handles PENDING UI States
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
            val tvStatus: TextView = view.findViewById(R.id.tvStatusLabel) // You'll need to add this to the item XML

            val tvInitials: TextView = view.findViewById(R.id.tvContactInitials)
            val imgAvatar: ImageView = view.findViewById(R.id.imgContactAvatar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trusted_contact, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            val contact = contacts[position]

            // Render Status Badges
            when (contact.status) {
                "PENDING" -> {
                    holder.tvName.text = "${contact.name} ⏳"
                    holder.tvName.alpha = 0.5f
                }
                "PENDING_RECEIVED" -> {
                    holder.tvName.text = "${contact.name} 🔔"
                }
                else -> {
                    if (contact.uid.isNotEmpty()) {
                        holder.tvName.text = "${contact.name} \uD83D\uDFE2" // Green Dot
                    } else {
                        holder.tvName.text = contact.name
                    }
                    holder.tvName.alpha = 1.0f
                }
            }

            holder.tvNumber.text = contact.number

            val isNightMode = (holder.itemView.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            applyAvatarBackground(holder.tvInitials, isNightMode)

            if (contact.photoUri.isNotEmpty()) {
                try {
                    val uri = Uri.parse(contact.photoUri)
                    val inputStream = holder.itemView.context.contentResolver.openInputStream(uri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)

                    if (bitmap != null) {
                        holder.imgAvatar.setImageBitmap(bitmap)
                        holder.imgAvatar.visibility = View.VISIBLE
                        holder.tvInitials.visibility = View.GONE
                    } else {
                        throw Exception("Cannot load local URI")
                    }
                    inputStream?.close()
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

            // If the request is inbound, change "EDIT" to "ACCEPT"
            if (contact.status == "PENDING_RECEIVED") {
                holder.tvEdit.text = "ACCEPT"
                holder.tvEdit.setTextColor(Color.parseColor("#10B981"))
                holder.tvEdit.setOnClickListener {
                    contact.status = "ACCEPTED"
                    saveContacts()
                    notifyItemChanged(position)

                    // Note: In a full app, you would also push an update to the OTHER user's database
                    // changing their "PENDING" tag to "ACCEPTED" so they know you accepted.
                    showSentryToast("Invite Accepted! You are now mutually connected.", isLong = false)
                }
            } else {
                holder.tvEdit.text = "EDIT"
                holder.tvEdit.setTextColor(Color.parseColor("#3B82F6"))
                holder.tvEdit.setOnClickListener { onEditClick(contact) }
            }

            holder.tvDelete.setOnClickListener { onDeleteClick(contact) }
        }

        override fun getItemCount() = contacts.size
    }

    private fun showSentryToast(message: String, isLong: Boolean) {
        val toast = Toast(requireContext())
        toast.duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#12151C"))
                setStroke(3, Color.parseColor("#3B82F6"))
            }
            setPadding(50, 30, 50, 30)
        }

        val icon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_sentry_half_gold)
            layoutParams = LinearLayout.LayoutParams(60, 75).apply {
                setMargins(0, 0, 30, 0)
            }
        }

        val textView = TextView(requireContext()).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        customLayout.addView(icon)
        customLayout.addView(textView)
        toast.view = customLayout
        toast.show()
    }
}