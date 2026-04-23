package com.example.aisecurity.ui.map

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aisecurity.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

data class MapContactSummary(
    val uid: String,
    val name: String,
    val colorHex: String,
    var locationName: String = "Locating...",
    var statusStr: String = "Connecting...",
    var isSOS: Boolean = false
)

@SuppressLint("MissingPermission")
class MapFragment : Fragment(), SensorEventListener, OnMapReadyCallback {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var googleMapContainer: View
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnSos: androidx.appcompat.widget.AppCompatButton

    private lateinit var btnNavMode: View
    private lateinit var tvNavMode: TextView
    private var isNavModeActive = false
    private var lastKnownBearing = 0f
    private var lastKnownCompassBearing = 0f

    private lateinit var recyclerMapContacts: RecyclerView
    private val mapContactsList = mutableListOf<MapContactSummary>()
    private lateinit var mapContactsAdapter: MapContactsAdapter

    private var gMap: GoogleMap? = null
    private var gMapMarker: com.google.android.gms.maps.model.Marker? = null

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private var isFirstLocationUpdate = true
    private var myCurrentLatLng: LatLng? = null

    private var myName: String = "Me"
    private var myPhotoUri: String = ""
    private var cachedMyBitmap: android.graphics.Bitmap? = null
    private var lastSosStateForBitmap = false
    private var isSosActive: Boolean = false
    private var isRemoteAiActive = false

    private val activeFirebaseListeners = mutableListOf<ListenerRegistration>()

    private val contactMarkersGMap = mutableMapOf<String, com.google.android.gms.maps.model.Marker>()
    private val contactLinesGMap = mutableMapOf<String, com.google.android.gms.maps.model.Polyline>()
    private val accuracyCirclesGMap = mutableMapOf<String, Circle>()

    private var lastRouteFetchTime = 0L
    private val routeCache = mutableMapOf<String, MutableList<LatLng>>()

    private var currentlySelectedUid: String? = null

    private val unlockedViaQrUids = mutableSetOf<String>()

    private var myArrivalTime = System.currentTimeMillis()
    private var myLastStationaryLatLng: LatLng? = null
    private val contactArrivalTimes = mutableMapOf<String, Long>()
    private val contactStationaryLatLngs = mutableMapOf<String, LatLng>()
    private val GEOFENCE_RADIUS_METERS = 30.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        googleMapContainer = view.findViewById(R.id.googleMapContainer)
        tvLocationStatus = view.findViewById(R.id.tvLocationStatus)
        tvNetworkStatus = view.findViewById(R.id.tvNetworkStatus)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnSos = view.findViewById(R.id.btnSos)
        recyclerMapContacts = view.findViewById(R.id.recyclerMapContacts)

        btnNavMode = view.findViewById(R.id.btnNavMode)
        tvNavMode = view.findViewById(R.id.tvNavMode)

        setupUI()

        val mapFragment = childFragmentManager.findFragmentById(R.id.googleMapContainer) as SupportMapFragment
        mapFragment.getMapAsync(this)

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    tvNetworkStatus.text = "ONLINE"
                    tvNetworkStatus.setTextColor(Color.parseColor("#10B981"))
                    tvNetworkStatus.background = GradientDrawable().apply { cornerRadius = 50f; setColor(Color.parseColor("#1A10B981")) }
                }
            }
            override fun onLost(network: Network) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    tvNetworkStatus.text = "OFFLINE"
                    tvNetworkStatus.setTextColor(Color.parseColor("#F59E0B"))
                    tvNetworkStatus.background = GradientDrawable().apply { cornerRadius = 50f; setColor(Color.parseColor("#1AF59E0B")) }
                }
            }
        }

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        btnSos.setOnClickListener { toggleSosProtocol() }

        if (!isHidden) {
            view.post {
                if (isAdded) toggleFullScreenMap(true)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        if (!isAdded) return 0
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    inner class MapContactsAdapter : RecyclerView.Adapter<MapContactsAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAvatar: TextView = view.findViewById(R.id.tvMapContactAvatar)
            val tvName: TextView = view.findViewById(R.id.tvMapContactName)
            val tvLocation: TextView = view.findViewById(R.id.tvMapContactLocation)
            val tvStatus: TextView = view.findViewById(R.id.tvMapContactStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_map_bottom_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = mapContactsList[position]

            holder.tvName.text = contact.name
            holder.tvAvatar.text = getInitials(contact.name)
            holder.tvLocation.text = contact.locationName
            holder.tvStatus.text = contact.statusStr

            val isNightMode = if (isAdded) isDarkMode() else false

            holder.itemView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                if (isNightMode) {
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(2, Color.parseColor("#334155"))
                } else {
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(2, Color.parseColor("#E2E8F0"))
                }
            }

            holder.tvAvatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(contact.colorHex))
            }

            holder.itemView.setOnClickListener {
                if (contact.uid == "ME") {
                    currentlySelectedUid = null
                    myCurrentLatLng?.let { latLng -> gMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng)) }
                    gMapMarker?.showInfoWindow()
                } else {
                    currentlySelectedUid = contact.uid
                    contactMarkersGMap[contact.uid]?.let { marker ->
                        gMap?.animateCamera(CameraUpdateFactory.newLatLng(marker.position))
                        marker.showInfoWindow()
                    }
                }
                updateAllTrackingLines()
            }
        }
        override fun getItemCount() = mapContactsList.size
    }

    private fun toggleFullScreenMap(isMapVisible: Boolean) {
        val currentActivity = activity ?: return
        if (!isAdded) return

        val appBar = currentActivity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        val toolbar = currentActivity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        val container = currentActivity.findViewById<View>(R.id.fragment_container)

        if (toolbar == null || container == null || appBar == null) return

        val params = container.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isMapVisible) {
            toolbar.setBackgroundResource(android.R.color.transparent)
            appBar.setBackgroundResource(android.R.color.transparent)
            appBar.elevation = 0f
            toolbar.setTitleTextColor(Color.TRANSPARENT)

            params.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            appBar.bringToFront()
            appBar.translationZ = 100f
        } else {
            toolbar.setBackgroundResource(R.color.ig_background)
            appBar.setBackgroundResource(R.color.ig_background)
            appBar.elevation = dpToPx(4).toFloat()

            val primaryTextColor = ContextCompat.getColor(requireContext(), R.color.ig_text_primary)
            toolbar.setTitleTextColor(primaryTextColor)

            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.appBarLayout
            appBar.translationZ = 0f
        }
        container.layoutParams = params
        container.requestLayout()
    }

    private fun setupUI() {
        val bottomSheet = requireView().findViewById<LinearLayout>(R.id.bottomSheet)
        val tvSecureTrackingTitle = requireView().findViewById<TextView>(R.id.tvSecureTrackingTitle)
        val dragHandle = requireView().findViewById<View>(R.id.dragHandle)
        val divider = requireView().findViewById<View>(R.id.divider)
        val isNightMode = isDarkMode()

        mapContactsAdapter = MapContactsAdapter()
        recyclerMapContacts.layoutManager = LinearLayoutManager(requireContext())
        recyclerMapContacts.adapter = mapContactsAdapter

        val cornerRadiiArray = floatArrayOf(80f, 80f, 80f, 80f, 0f, 0f, 0f, 0f)

        if (!isNightMode) {
            dragHandle.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 50f; setColor(Color.parseColor("#CBD5E1")) }
            bottomSheet.background = GradientDrawable().apply { cornerRadii = cornerRadiiArray; setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E2E8F0")) }
            tvSecureTrackingTitle.setTextColor(Color.parseColor("#64748B"))
            tvLocationStatus.setTextColor(Color.parseColor("#0F172A"))
            divider.setBackgroundColor(Color.parseColor("#F1F5F9"))
        } else {
            dragHandle.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 50f; setColor(Color.parseColor("#475569")) }
            bottomSheet.background = GradientDrawable().apply { cornerRadii = cornerRadiiArray; setColor(Color.parseColor("#12151C")); setStroke(2, Color.parseColor("#1E293B")) }
            tvSecureTrackingTitle.setTextColor(Color.parseColor("#94A3B8"))
            tvLocationStatus.setTextColor(Color.parseColor("#F8FAFC"))
            divider.setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val sosBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            setStroke(4, Color.parseColor("#EF4444"))
        }
        if (isNightMode) {
            sosBg.setColor(Color.parseColor("#1A0000"))
            btnSos.setTextColor(Color.parseColor("#EF4444"))
        } else {
            sosBg.setColor(Color.parseColor("#FEF2F2"))
            btnSos.setTextColor(Color.parseColor("#DC2626"))
        }
        btnSos.background = sosBg
        applyGlassButton(btnSettings, isNightMode)

        btnSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this@MapFragment)
                .add(R.id.fragment_container, com.example.aisecurity.ui.settings.TrustedContactsFragment())
                .addToBackStack("TrustedContacts")
                .commit()
        }

        updateNavButtonUI(isNightMode)
        btnNavMode.setOnClickListener {
            isNavModeActive = !isNavModeActive
            updateNavButtonUI(isNightMode)

            myCurrentLatLng?.let { latLng ->
                if (isNavModeActive) {
                    val camPos = com.google.android.gms.maps.model.CameraPosition.Builder()
                        .target(latLng)
                        .zoom(19.5f)
                        .tilt(65f)
                        .bearing(lastKnownBearing)
                        .build()
                    gMap?.animateCamera(CameraUpdateFactory.newCameraPosition(camPos))
                } else {
                    val camPos = com.google.android.gms.maps.model.CameraPosition.Builder()
                        .target(latLng)
                        .zoom(17f)
                        .tilt(0f)
                        .bearing(0f)
                        .build()
                    gMap?.animateCamera(CameraUpdateFactory.newCameraPosition(camPos))
                }
            }
        }
    }

    private fun updateNavButtonUI(isNightMode: Boolean) {
        if (isNavModeActive) {
            btnNavMode.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 32f; setColor(Color.parseColor("#3B82F6")) }
            tvNavMode.setTextColor(Color.WHITE)
        } else {
            if (isNightMode) {
                btnNavMode.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 32f; setColor(Color.parseColor("#12151C")); setStroke(2, Color.parseColor("#1E293B")) }
                tvNavMode.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                btnNavMode.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 32f; setColor(Color.parseColor("#FFFFFF")); setStroke(2, Color.parseColor("#E2E8F0")) }
                tvNavMode.setTextColor(Color.parseColor("#64748B"))
            }
        }
    }

    private fun toggleSosProtocol() {
        if (isSosActive) {
            isSosActive = false
            updateSosDatabaseState()
            Toast.makeText(requireContext(), "✅ Emergency Cancelled.", Toast.LENGTH_SHORT).show()
        } else {
            showSosConfirmationDialog()
        }
    }

    private fun showSosConfirmationDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_sos_confirmation, null)
        val dialogRoot = dialogView.findViewById<LinearLayout>(R.id.dialogRoot)
        val etCode = dialogView.findViewById<android.widget.EditText>(R.id.etConfirmationCode)
        val btnGetCode = dialogView.findViewById<TextView>(R.id.btnGetCode)
        val btnCancel = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnCancelSos)
        val btnVerify = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnVerifySos)

        val isNightMode = isDarkMode()
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

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnGetCode.setOnClickListener {
            btnGetCode.text = "SENT!"
            btnGetCode.isEnabled = false
            btnGetCode.setTextColor(Color.GRAY)
            Toast.makeText(requireContext(), "Test Code Sent: 123456", Toast.LENGTH_LONG).show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnVerify.setOnClickListener {
            dialog.dismiss()
            isSosActive = true
            updateSosDatabaseState()
            Toast.makeText(requireContext(), "🚨 PROTOCOL ENGAGED! Remote access granted.", Toast.LENGTH_LONG).show()
        }

        dialog.show()
    }

    // ══════════════════════════════════════════════════════
    //  REMOTE CONTROL PANEL (LIVE COMMAND CENTER)
    // ══════════════════════════════════════════════════════
    private fun showRemoteControlPanel(targetName: String, targetUid: String? = null) {
        if (!isAdded) return

        if (targetUid != null && targetUid.isNotEmpty()) {
            currentlySelectedUid = targetUid
            updateAllTrackingLines()
            contactMarkersGMap[targetUid]?.let { marker ->
                gMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 18.5f), 1500, null)
                marker.showInfoWindow()
            }
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_remote_control, null)

        val isNight = isDarkMode()
        val mainBgColor = if (isNight) Color.parseColor("#0A0E1A") else Color.parseColor("#F1F5F9")
        val strokeColor = if (isNight) Color.parseColor("#252F48") else Color.parseColor("#CBD5E1")
        val cardBgColor = if (isNight) Color.parseColor("#1C2237") else Color.parseColor("#FFFFFF")
        val textPrimary = if (isNight) Color.parseColor("#FFFFFF") else Color.parseColor("#0F172A")

        val rootChassis = dialogView.findViewById<MaterialCardView>(R.id.dialogRoot)
        rootChassis?.setCardBackgroundColor(mainBgColor)
        rootChassis?.strokeColor = strokeColor

        val cardIds = intArrayOf(
            R.id.cardProfile, R.id.cardConnectivity, R.id.cardAiDetection,
            R.id.cardLockType, R.id.cardCamera, R.id.cardLoadBalance, R.id.cardLocation
        )
        for (id in cardIds) {
            dialogView.findViewById<CardView>(id)?.setCardBackgroundColor(cardBgColor)
        }

        val textPrimaryIds = intArrayOf(
            R.id.tvTargetName, R.id.tvWifiStatus, R.id.tvMobileDataStatus, R.id.tvLocationStatus,
            R.id.tvLocationCity, R.id.tvLatitude, R.id.tvLongitude, R.id.tvBluetoothStatus,
            R.id.tvBatterySaverStatus, R.id.tvRemoteDistance
        )
        for (id in textPrimaryIds) {
            dialogView.findViewById<TextView>(id)?.setTextColor(textPrimary)
        }

        val tvUserName = dialogView.findViewById<TextView>(R.id.tvTargetName)
        tvUserName?.text = targetName

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        setupToggles(dialogView, targetUid, dialog)
        setupAiSensitivityDropdown(dialogView)
        setupAiButton(dialogView)

        dialog.show()
    }

    private fun setupToggles(dialogView: View, targetUid: String?, dialog: AlertDialog) {
        val switchWifi = dialogView.findViewById<SwitchMaterial>(R.id.switchWifi)
        val tvWifiStatus = dialogView.findViewById<TextView>(R.id.tvWifiStatus)
        val iconWifi = dialogView.findViewById<View>(R.id.iconWifi)

        val switchMd = dialogView.findViewById<SwitchMaterial>(R.id.switchData)
        val tvMdStatus = dialogView.findViewById<TextView>(R.id.tvMobileDataStatus)
        val iconData = dialogView.findViewById<View>(R.id.iconData)

        val switchLoc = dialogView.findViewById<SwitchMaterial>(R.id.switchLocation)
        val tvLocStatus = dialogView.findViewById<TextView>(R.id.tvLocationStatus)
        val iconLoc = dialogView.findViewById<View>(R.id.iconLoc)

        val switchBluetooth = dialogView.findViewById<SwitchMaterial>(R.id.switchBluetooth)
        val tvBluetoothStatus = dialogView.findViewById<TextView>(R.id.tvBluetoothStatus)
        val iconBluetooth = dialogView.findViewById<View>(R.id.iconBluetooth)

        val switchBatterySaver = dialogView.findViewById<SwitchMaterial>(R.id.switchBatterySaver)
        val tvBatterySaverStatus = dialogView.findViewById<TextView>(R.id.tvBatterySaverStatus)
        val iconBatterySaver = dialogView.findViewById<View>(R.id.iconBatterySaver)

        var isUpdatingFromFirebase = false

        if (targetUid != null && targetUid.isNotEmpty()) {
            val listener = db.collection("Users").document(targetUid).addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                isUpdatingFromFirebase = true

                val wifiOn = snapshot.getBoolean("state_wifi") ?: false
                val btOn = snapshot.getBoolean("state_bluetooth") ?: false
                val dataOn = snapshot.getBoolean("state_mobile_data") ?: false
                val locOn = snapshot.getBoolean("state_location") ?: false
                val saverOn = snapshot.getBoolean("state_battery_saver") ?: false

                if (switchWifi?.isChecked != wifiOn) switchWifi?.isChecked = wifiOn
                if (switchBluetooth?.isChecked != btOn) switchBluetooth?.isChecked = btOn
                if (switchMd?.isChecked != dataOn) switchMd?.isChecked = dataOn
                if (switchLoc?.isChecked != locOn) switchLoc?.isChecked = locOn
                if (switchBatterySaver?.isChecked != saverOn) switchBatterySaver?.isChecked = saverOn

                isUpdatingFromFirebase = false
            }
            dialog.setOnDismissListener { listener.remove() }
        }

        fun sendCommand(commandField: String, state: Boolean) {
            if (!isUpdatingFromFirebase && targetUid != null) {
                db.collection("Users").document(targetUid).set(hashMapOf(commandField to state), SetOptions.merge())
            }
        }

        switchWifi?.setOnCheckedChangeListener { _, isOn ->
            tvWifiStatus?.text = if (isOn) "Connected" else "Disconnected"
            iconWifi?.alpha = if (isOn) 1f else 0.3f
            sendCommand("cmd_wifi", isOn)
        }

        switchMd?.setOnCheckedChangeListener { _, isOn ->
            tvMdStatus?.text = if (isOn) "4G LTE" else "Off"
            iconData?.alpha = if (isOn) 1f else 0.3f
            sendCommand("cmd_mobile_data", isOn)
        }

        switchLoc?.setOnCheckedChangeListener { _, isOn ->
            tvLocStatus?.text = if (isOn) "On" else "Off"
            iconLoc?.alpha = if (isOn) 1f else 0.35f
            sendCommand("cmd_location", isOn)
        }

        switchBluetooth?.setOnCheckedChangeListener { _, isOn ->
            tvBluetoothStatus?.text = if (isOn) "On" else "Off"
            iconBluetooth?.alpha = if (isOn) 1f else 0.3f
            sendCommand("cmd_bluetooth", isOn)
        }

        switchBatterySaver?.setOnCheckedChangeListener { _, isOn ->
            tvBatterySaverStatus?.text = if (isOn) "On" else "Off"
            iconBatterySaver?.alpha = if (isOn) 1f else 0.35f
            sendCommand("cmd_battery_saver", isOn)
        }

        dialogView.findViewById<LinearLayout>(R.id.rowWifi)?.setOnClickListener { switchWifi?.toggle() }
        dialogView.findViewById<LinearLayout>(R.id.rowMobileData)?.setOnClickListener { switchMd?.toggle() }
        dialogView.findViewById<LinearLayout>(R.id.rowLocation)?.setOnClickListener { switchLoc?.toggle() }
        dialogView.findViewById<LinearLayout>(R.id.rowBluetooth)?.setOnClickListener { switchBluetooth?.toggle() }
        dialogView.findViewById<LinearLayout>(R.id.rowBatterySaver)?.setOnClickListener { switchBatterySaver?.toggle() }
    }

    private fun setupAiSensitivityDropdown(dialogView: View) {
        val panel = dialogView.findViewById<LinearLayout>(R.id.dropPanelAi)
        val chevron = dialogView.findViewById<ImageView>(R.id.ivAiChevron)
        val tvValue = dialogView.findViewById<TextView>(R.id.tvAiSensitivityValue)
        val trigger = dialogView.findViewById<View>(R.id.btnAiSensitivityDrop)

        val options = listOf(
            R.id.optAiStrict to Pair(R.id.chkAiStrict, "Strict"),
            R.id.optAiLenient to Pair(R.id.chkAiLenient, "Lenient")
        )

        trigger?.setOnClickListener { toggleDropdown(panel, chevron) }

        options.forEach { (optId, pair) ->
            val (chkId, label) = pair
            dialogView.findViewById<LinearLayout>(optId)?.setOnClickListener {
                tvValue?.text = label
                selectOption(dialogView, options, chkId)
                collapsePanel(panel, chevron)
                Toast.makeText(requireContext(), "Command: Setting Sensitivity to $label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAiButton(dialogView: View) {
        val btnUseAi = dialogView.findViewById<Button>(R.id.btnUseAiToggle)
        btnUseAi?.setOnClickListener {
            isRemoteAiActive = !isRemoteAiActive
            if (isRemoteAiActive) {
                btnUseAi.text = "STOP"
                btnUseAi.setBackgroundColor(Color.parseColor("#EF4444"))
                Toast.makeText(requireContext(), "AI Detection Stopped", Toast.LENGTH_SHORT).show()
            } else {
                btnUseAi.text = "USE AI"
                btnUseAi.setBackgroundColor(Color.parseColor("#0284C7"))
                Toast.makeText(requireContext(), "AI Detection Activated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleDropdown(panel: LinearLayout?, chevron: ImageView?) {
        if (panel == null || chevron == null) return
        if (panel.visibility == View.GONE) expandPanel(panel, chevron)
        else collapsePanel(panel, chevron)
    }

    private fun expandPanel(panel: LinearLayout, chevron: ImageView) {
        panel.visibility = View.VISIBLE
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val targetH = panel.measuredHeight
        panel.layoutParams.height = 0
        panel.requestLayout()

        ValueAnimator.ofInt(0, targetH).apply {
            duration = 250
            addUpdateListener {
                panel.layoutParams.height = it.animatedValue as Int
                panel.requestLayout()
            }
            start()
        }
        rotatChevron(chevron, 0f, 180f)
    }

    private fun collapsePanel(panel: LinearLayout, chevron: ImageView) {
        val initH = panel.measuredHeight
        ValueAnimator.ofInt(initH, 0).apply {
            duration = 220
            addUpdateListener {
                val h = it.animatedValue as Int
                panel.layoutParams.height = h
                panel.requestLayout()
                if (h == 0) panel.visibility = View.GONE
            }
            start()
        }
        rotatChevron(chevron, 180f, 0f)
    }

    private fun rotatChevron(view: ImageView, from: Float, to: Float) {
        val anim = RotateAnimation(from, to, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 220
            fillAfter = true
        }
        view.startAnimation(anim)
    }

    private fun selectOption(dialogView: View, options: List<Pair<Int, Pair<Int, String>>>, selectedChkId: Int) {
        options.forEach { (_, pair) ->
            val (chkId, _) = pair
            val chk = dialogView.findViewById<ImageView>(chkId)
            chk?.visibility = if (chkId == selectedChkId) View.VISIBLE else View.INVISIBLE

            val row = chk?.parent as? LinearLayout
            val lbl = row?.getChildAt(1) as? TextView
            if (chkId == selectedChkId) {
                lbl?.setTextColor(Color.parseColor("#00D8FF"))
            } else {
                lbl?.setTextColor(Color.parseColor("#8899BB"))
            }
        }
    }

    private fun updateSosDatabaseState() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("Users").document(userId).set(hashMapOf("isSOS" to isSosActive), SetOptions.merge())

        myCurrentLatLng?.let {
            processLocationUpdate(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = it.latitude
                longitude = it.longitude
                accuracy = 10f
            })
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        view?.post {
            if (isAdded) toggleFullScreenMap(!hidden)
        }

        if (hidden) {
            stopLocationTracking()
            sensorManager.unregisterListener(this)
            activeFirebaseListeners.forEach { it.remove() }
            activeFirebaseListeners.clear()
        } else {
            lastRouteFetchTime = 0L
            val userId = auth.currentUser?.uid
            if (userId != null) {
                startLocationTracking(userId)
            }
            rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

            checkAndProcessQrIntent()

            initLife360Engine()
        }
    }

    private fun applyGlassButton(button: Button, isNightMode: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE;
            cornerRadius = 1000f;
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

    inner class CustomGoogleInfoWindowAdapter : GoogleMap.InfoWindowAdapter {
        override fun getInfoWindow(marker: com.google.android.gms.maps.model.Marker): View? {
            val view = layoutInflater.inflate(R.layout.layout_map_info_window, null)

            val isNight = isDarkMode()
            val mainBgColor = if (isNight) Color.parseColor("#0A0E1A") else Color.parseColor("#F1F5F9")
            val strokeColor = if (isNight) Color.parseColor("#252F48") else Color.parseColor("#CBD5E1")
            val cardBgColor = if (isNight) Color.parseColor("#1C2237") else Color.parseColor("#FFFFFF")
            val textPrimary = if (isNight) Color.parseColor("#FFFFFF") else Color.parseColor("#0F172A")

            val rootChassis = view.findViewById<MaterialCardView>(R.id.dialogRoot)
            rootChassis?.setCardBackgroundColor(mainBgColor)
            rootChassis?.strokeColor = strokeColor

            val cardIds = intArrayOf(
                R.id.cardProfile, R.id.cardConnectivity, R.id.cardAiDetection,
                R.id.cardLocation
            )
            for (id in cardIds) {
                view.findViewById<CardView>(id)?.setCardBackgroundColor(cardBgColor)
            }

            val textPrimaryIds = intArrayOf(
                R.id.tvTargetName, R.id.tvLocationStatus, R.id.tvLocationCity,
                R.id.tvLatitude, R.id.tvLongitude, R.id.tvRemoteDistance
            )
            for (id in textPrimaryIds) {
                view.findViewById<TextView>(id)?.setTextColor(textPrimary)
            }

            try {
                val data = JSONObject(marker.snippet ?: "{}")

                val targetName = data.getString("name")
                view.findViewById<TextView>(R.id.tvTargetName)?.text = targetName
                view.findViewById<TextView>(R.id.tvLocationStatus)?.text = data.getString("status")
                view.findViewById<TextView>(R.id.tvRemoteDistance)?.text = "DIST: ${data.getString("distance")}"

                val battery = data.getInt("battery")
                val tvBatt = view.findViewById<TextView>(R.id.tvBattery)
                tvBatt?.text = "$battery%"
                if (battery <= 20) tvBatt?.setTextColor(Color.parseColor("#EF4444"))

            } catch (e: Exception) {
                view.findViewById<TextView>(R.id.tvTargetName)?.text = marker.title
            }
            return view
        }
        override fun getInfoContents(marker: com.google.android.gms.maps.model.Marker): View? = null
    }

    private fun getBatteryLevel(): Int {
        val bm = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000f)
    }

    private fun formatDetailedTime(millis: Long): String {
        val totalMins = millis / 60000
        if (totalMins < 1) return "Just now"

        val days = totalMins / (24 * 60)
        val hours = (totalMins % (24 * 60)) / 60
        val mins = totalMins % 60

        val parts = mutableListOf<String>()
        if (days > 0) parts.add("${days}d")
        if (hours > 0) parts.add("${hours}h")
        if (mins > 0 && days == 0L) parts.add("${mins}m")

        return parts.joinToString(" ")
    }

    private fun getInitials(name: String): String {
        return name.trim().split("\\s+".toRegex()).mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
    }

    private fun createMarkerBitmap(context: Context, name: String, photoUri: String, arrowColorHex: String): android.graphics.Bitmap? {
        try {
            val density = context.resources.displayMetrics.density
            val size = (76 * density).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            val center = size / 2f
            val radius = 26 * density

            paint.color = android.graphics.Color.parseColor(arrowColorHex)
            val path = android.graphics.Path()
            path.moveTo(center, 2 * density)
            path.lineTo(center - (10 * density), 18 * density)
            path.lineTo(center + (10 * density), 18 * density)
            path.close()

            paint.setShadowLayer(4f, 0f, 2f, android.graphics.Color.argb(80, 0, 0, 0))
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()

            paint.color = android.graphics.Color.WHITE
            paint.setShadowLayer(6f, 0f, 3f, android.graphics.Color.argb(100, 0, 0, 0))
            canvas.drawCircle(center, center, radius, paint)
            paint.clearShadowLayer()

            val innerRadius = radius - (3 * density)
            var imageDrawn = false
            if (photoUri.isNotEmpty()) {
                try {
                    val uri = Uri.parse(photoUri)
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val rawBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (rawBitmap != null) {
                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(rawBitmap, (innerRadius * 2).toInt(), (innerRadius * 2).toInt(), true)
                        val shader = android.graphics.BitmapShader(scaledBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                        val shaderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        shaderPaint.shader = shader
                        canvas.translate(center - innerRadius, center - innerRadius)
                        canvas.drawCircle(innerRadius, innerRadius, innerRadius, shaderPaint)
                        canvas.translate(-(center - innerRadius), -(center - innerRadius))
                        imageDrawn = true
                    }
                } catch (e: Exception) { Log.e("AVATAR_ERROR", "Error: ${e.message}") }
            }

            if (!imageDrawn) {
                paint.color = android.graphics.Color.parseColor("#E0E7FF")
                canvas.drawCircle(center, center, innerRadius, paint)
                paint.color = android.graphics.Color.parseColor("#1E3A8A")
                paint.textSize = 20f * context.resources.displayMetrics.scaledDensity
                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                val initials = getInitials(name)
                val textBounds = android.graphics.Rect()
                paint.getTextBounds(initials, 0, initials.length, textBounds)
                val textY = center + (textBounds.height() / 2f)
                canvas.drawText(initials, center, textY, paint)
            }
            return bitmap
        } catch (e: Exception) { return null }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        if (gMap != null) return
        gMap = googleMap
        gMap?.setInfoWindowAdapter(CustomGoogleInfoWindowAdapter())
        gMap?.setPadding(0, dpToPx(90), 0, 0)

        if (isDarkMode()) {
            try { gMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark)) } catch (e: Exception) {}
        }

        gMapMarker = gMap?.addMarker(MarkerOptions()
            .position(LatLng(0.0, 0.0))
            .title("Me")
            .anchor(0.5f, 0.5f)
            .infoWindowAnchor(0.5f, 0.5f)
            .flat(true)
            .visible(false))

        gMap?.setOnInfoWindowClickListener { marker ->
            if (marker.title != "Me") {
                try {
                    val data = JSONObject(marker.snippet ?: "{}")
                    val isSOS = data.optBoolean("isSOS", false)
                    val targetName = data.optString("name", "")
                    val targetUid = data.optString("uid", "")

                    val isLostDevice = targetName.contains("Lost Device")
                    val isTrustedUser = mapContactsList.any { it.uid == targetUid }

                    if (isSOS || isLostDevice || isTrustedUser || unlockedViaQrUids.contains(targetUid)) {
                        showRemoteControlPanel(targetName, targetUid)
                    } else {
                        Toast.makeText(requireContext(), "Access Denied. Device is not in SOS Mode.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {}
            }
        }

        gMap?.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE && isNavModeActive) {
                isNavModeActive = false
                updateNavButtonUI(isDarkMode())
            }
        }

        gMap?.setOnMarkerClickListener { clickedMarker ->
            val clickedUid = clickedMarker.tag as? String
            if (clickedUid != null) {
                currentlySelectedUid = if (currentlySelectedUid == clickedUid) null else clickedUid
                updateAllTrackingLines()
                clickedMarker.showInfoWindow()
                return@setOnMarkerClickListener true
            } else if (clickedMarker.title == "Me") {
                currentlySelectedUid = null
                updateAllTrackingLines()
                clickedMarker.showInfoWindow()
                return@setOnMarkerClickListener true
            }
            false
        }

        updateAllTrackingLines()
    }

    override fun onResume() {
        super.onResume()
        lastRouteFetchTime = 0L

        if (!isHidden) {
            view?.post { if(isAdded) toggleFullScreenMap(true) }
        }

        val networkRequest = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("Users").document(userId).get().addOnSuccessListener { doc ->
                if (doc != null && doc.exists() && isAdded) {
                    myName = doc.getString("fullName") ?: "Me"
                    myPhotoUri = doc.getString("photoUri") ?: ""

                    startLocationTracking(userId)
                } else {
                    startLocationTracking(userId)
                }
            }.addOnFailureListener { startLocationTracking(userId) }
        }

        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        if (!isHidden) {
            checkAndProcessQrIntent()
            initLife360Engine()
        }
    }

    fun checkAndProcessQrIntent() {
        val currentActivity = activity ?: return
        val intent = currentActivity.intent
        val data: Uri? = intent?.data

        if (data != null && data.scheme == "https" && data.host == "bioguard-efb32.web.app") {
            val targetUid = data.getQueryParameter("uid")
            val targetName = data.getQueryParameter("name") ?: "Lost Device"

            if (targetUid != null && targetUid != auth.currentUser?.uid) {
                unlockedViaQrUids.add(targetUid)
                autoAddLostDevice(targetUid, targetName)
                intent.data = null
            } else if (targetUid == null && targetName != "Lost Device") {
                findUidAndAddLostDevice(targetName)
                intent.data = null
            }
        }
    }

    private fun findUidAndAddLostDevice(identifier: String) {
        db.collection("Users").whereEqualTo("email", identifier).get().addOnSuccessListener { docs ->
            if (!docs.isEmpty) {
                val foundUid = docs.documents[0].id
                unlockedViaQrUids.add(foundUid)
                autoAddLostDevice(foundUid, identifier)
            } else {
                db.collection("Users").whereEqualTo("phoneNumber", identifier).get().addOnSuccessListener { phoneDocs ->
                    if (!phoneDocs.isEmpty) {
                        val foundUid = phoneDocs.documents[0].id
                        unlockedViaQrUids.add(foundUid)
                        autoAddLostDevice(foundUid, identifier)
                    } else {
                        Toast.makeText(requireContext(), "Account not found in database.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // 🚨 INSTANT DIALOG LOGIC: Bypasses the Map Marker wait sequence
    private fun autoAddLostDevice(uid: String, fallbackName: String) {
        val myUid = auth.currentUser?.uid ?: return

        db.collection("Users").document(myUid).get().addOnSuccessListener { currentUserDoc ->
            val existingList = currentUserDoc.get("trustedContacts") as? MutableList<Map<String, String>> ?: mutableListOf()

            if (existingList.none { it["uid"] == uid }) {

                db.collection("Users").document(uid).get().addOnSuccessListener { targetUserDoc ->
                    var actualName = fallbackName
                    var actualNumber = "Tracking Protocol Active"
                    var actualPhoto = ""

                    if (targetUserDoc.exists()) {
                        actualName = targetUserDoc.getString("fullName") ?: targetUserDoc.getString("name") ?: fallbackName
                        actualNumber = targetUserDoc.getString("phoneNumber") ?: targetUserDoc.getString("number") ?: "No Number Provided"
                        actualPhoto = targetUserDoc.getString("photoUri") ?: ""
                    }

                    val newContact = mapOf(
                        "id" to java.util.UUID.randomUUID().toString(),
                        "name" to "$actualName (Lost Device)",
                        "number" to actualNumber,
                        "photoUri" to actualPhoto,
                        "uid" to uid
                    )
                    existingList.add(newContact)

                    db.collection("Users").document(myUid).set(mapOf("trustedContacts" to existingList), SetOptions.merge())
                    Toast.makeText(requireContext(), "✅ Target Acquired! $actualName added to tracking list.", Toast.LENGTH_LONG).show()

                    val theirContacts = targetUserDoc.get("trustedContacts") as? MutableList<Map<String, String>> ?: mutableListOf()
                    if (theirContacts.none { it["uid"] == myUid }) {
                        val myNameSaved = currentUserDoc.getString("fullName") ?: currentUserDoc.getString("name") ?: "Sentry User"
                        val myPhoneSaved = currentUserDoc.getString("phoneNumber") ?: currentUserDoc.getString("number") ?: ""
                        val myPhotoSaved = currentUserDoc.getString("photoUri") ?: ""

                        val myContactForThem = mapOf(
                            "id" to java.util.UUID.randomUUID().toString(),
                            "name" to myNameSaved,
                            "number" to myPhoneSaved,
                            "photoUri" to myPhotoSaved,
                            "uid" to myUid
                        )
                        theirContacts.add(myContactForThem)
                        db.collection("Users").document(uid).set(mapOf("trustedContacts" to theirContacts), SetOptions.merge())
                    }

                    // 🚨 FORCES INSTANT UI LOAD
                    showRemoteControlPanel(actualName, uid)
                    initLife360Engine()

                }.addOnFailureListener {
                    val newContact = mapOf(
                        "id" to java.util.UUID.randomUUID().toString(),
                        "name" to "$fallbackName (Lost Device)",
                        "number" to "Tracking Protocol Active",
                        "photoUri" to "",
                        "uid" to uid
                    )
                    existingList.add(newContact)

                    db.collection("Users").document(myUid).set(mapOf("trustedContacts" to existingList), SetOptions.merge())
                    Toast.makeText(requireContext(), "✅ Target Acquired! Lost Device added to tracking list.", Toast.LENGTH_LONG).show()

                    // 🚨 FORCES INSTANT UI LOAD
                    showRemoteControlPanel("$fallbackName (Lost Device)", uid)
                    initLife360Engine()
                }

            } else {
                Toast.makeText(requireContext(), "Device is already being tracked.", Toast.LENGTH_SHORT).show()

                // 🚨 FORCES INSTANT UI LOAD
                val actualName = mapContactsList.find { it.uid == uid }?.name ?: fallbackName
                showRemoteControlPanel(actualName, uid)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            lastKnownCompassBearing = (azimuth + 360) % 360

            gMapMarker?.rotation = lastKnownCompassBearing

            if (isNavModeActive && myCurrentLatLng != null) {
                val camPos = com.google.android.gms.maps.model.CameraPosition.Builder()
                    .target(myCurrentLatLng!!)
                    .zoom(19.5f)
                    .tilt(65f)
                    .bearing(lastKnownCompassBearing)
                    .build()
                gMap?.moveCamera(CameraUpdateFactory.newCameraPosition(camPos))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private val locationListener = LocationListener { location ->
        processLocationUpdate(location)
    }

    private fun startLocationTracking(userId: String) {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener)

            val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastLoc?.let { processLocationUpdate(it) }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationTracking() {
        locationManager.removeUpdates(locationListener)
    }

    private fun processLocationUpdate(location: Location) {
        if (!isAdded) return
        val currentLat = location.latitude
        val currentLng = location.longitude
        val currentAccuracy = location.accuracy
        val speed = location.speed

        val currentLatlng = LatLng(currentLat, currentLng)
        myCurrentLatLng = currentLatlng

        if (location.hasBearing() && speed > 1.0f) {
            lastKnownBearing = location.bearing
        } else {
            lastKnownBearing = lastKnownCompassBearing
        }

        if (currentAccuracy < 50f) {
            if (myLastStationaryLatLng == null) {
                myLastStationaryLatLng = currentLatlng
                myArrivalTime = System.currentTimeMillis()
            } else {
                val distMoved = distanceBetween(myLastStationaryLatLng!!.latitude, myLastStationaryLatLng!!.longitude, currentLat, currentLng)
                if (distMoved > GEOFENCE_RADIUS_METERS) {
                    myLastStationaryLatLng = currentLatlng
                    myArrivalTime = System.currentTimeMillis()
                }
            }
        }

        val myDwellMillis = System.currentTimeMillis() - myArrivalTime
        val currentBattery = getBatteryLevel()

        val timeStr = formatDetailedTime(myDwellMillis)
        val finalStatus = if (timeStr == "Just now") "Just arrived" else "Stationary for $timeStr"

        val myJsonData = JSONObject().apply {
            put("name", "Me")
            put("status", finalStatus)
            put("distance", "0m")
            put("battery", currentBattery)
            put("isSOS", isSosActive)
        }.toString()

        val safeContext = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var placeName = "Tracking Location..."
            try {
                val addresses = Geocoder(safeContext, Locale.getDefault()).getFromLocation(currentLat, currentLng, 1)
                if (!addresses.isNullOrEmpty()) placeName = addresses[0].getAddressLine(0) ?: "Unknown Street"
            } catch (e: Exception) { }

            val userId = auth.currentUser?.uid ?: return@launch
            val locationData = hashMapOf(
                "currentLat" to currentLat,
                "currentLng" to currentLng,
                "accuracy" to currentAccuracy,
                "battery" to currentBattery,
                "isSOS" to isSosActive,
                "bearing" to lastKnownBearing,
                "placeName" to placeName,
                "lastUpdated" to com.google.firebase.Timestamp.now()
            )
            db.collection("Users").document(userId).set(locationData, SetOptions.merge())

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext

                if (gMapMarker == null) {
                    gMapMarker = gMap?.addMarker(MarkerOptions().position(currentLatlng).title("Me").anchor(0.5f, 0.5f).flat(true))
                }

                val myArrowColor = if (isSosActive) "#EF4444" else "#3B82F6"
                if (cachedMyBitmap == null || lastSosStateForBitmap != isSosActive) {
                    cachedMyBitmap = createMarkerBitmap(safeContext, myName, myPhotoUri, myArrowColor)
                    lastSosStateForBitmap = isSosActive
                }

                cachedMyBitmap?.let {
                    gMapMarker?.setIcon(BitmapDescriptorFactory.fromBitmap(it))
                }

                gMapMarker?.position = currentLatlng
                gMapMarker?.rotation = lastKnownBearing
                gMapMarker?.snippet = myJsonData
                gMapMarker?.isVisible = true
                if (gMapMarker?.isInfoWindowShown == true) gMapMarker?.showInfoWindow()

                mapContactsList.firstOrNull { it.uid == "ME" }?.let {
                    it.locationName = placeName
                    it.statusStr = finalStatus
                    mapContactsAdapter.notifyItemChanged(0)
                }

                updateAllTrackingLines()

                if (!isNavModeActive && isFirstLocationUpdate) {
                    gMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatlng, 18f))
                    isFirstLocationUpdate = false
                    initLife360Engine()
                } else if (isNavModeActive) {
                    gMap?.animateCamera(CameraUpdateFactory.newLatLng(currentLatlng), 1000, null)
                }
                tvLocationStatus.text = placeName
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationTracking()
        sensorManager.unregisterListener(this)
        activeFirebaseListeners.forEach { it.remove() }
        activeFirebaseListeners.clear()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isAdded) toggleFullScreenMap(false)
        gMap?.clear()
        gMap = null
    }

    private fun initLife360Engine() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            startTrackingEngine()
            return
        }

        db.collection("Users").document(userId).get().addOnSuccessListener { doc ->
            if (doc != null && doc.contains("trustedContacts") && isAdded) {
                try {
                    val cloudContacts = doc.get("trustedContacts") as? List<Map<String, String>>
                    if (cloudContacts != null) {
                        val jsonArray = JSONArray()
                        for (c in cloudContacts) {
                            val obj = JSONObject().apply {
                                put("id", c["id"] ?: "")
                                put("name", c["name"] ?: "")
                                put("number", c["number"] ?: "")
                                put("photoUri", c["photoUri"] ?: "")
                                put("uid", c["uid"] ?: "")
                            }
                            jsonArray.put(obj)
                        }

                        val currentActivity = activity
                        if (currentActivity != null) {
                            currentActivity.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                                .edit().putString("trusted_contacts_json", jsonArray.toString()).apply()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            if (isAdded) startTrackingEngine()
        }.addOnFailureListener {
            if (isAdded) startTrackingEngine()
        }
    }

    private fun startTrackingEngine() {
        activeFirebaseListeners.forEach { it.remove() }
        activeFirebaseListeners.clear()

        val currentActivity = activity ?: return
        val prefs = currentActivity.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("trusted_contacts_json", "[]") ?: "[]"
        val jsonArray = org.json.JSONArray(jsonStr)

        val validUids = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            val uid = jsonArray.getJSONObject(i).optString("uid", "")
            if (uid.isNotEmpty()) validUids.add(uid)
        }

        val uidsToRemove = contactMarkersGMap.keys.filter { it !in validUids }
        uidsToRemove.forEach { uid ->
            contactMarkersGMap[uid]?.remove()
            contactLinesGMap[uid]?.remove()
            accuracyCirclesGMap[uid]?.remove()
            contactMarkersGMap.remove(uid)
            contactLinesGMap.remove(uid)
            accuracyCirclesGMap.remove(uid)
            routeCache.remove(uid)
        }

        val oldContacts = mapContactsList.associateBy { it.uid }
        mapContactsList.clear()

        val beautifulColors = arrayOf("#8B5CF6", "#F97316", "#10B981", "#EF4444", "#3B82F6")

        val oldMe = oldContacts["ME"]
        mapContactsList.add(MapContactSummary(
            "ME", "Me", "#3B82F6",
            oldMe?.locationName ?: "Locating...",
            oldMe?.statusStr ?: "Connecting..."
        ))

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val uid = obj.optString("uid", "")
            val name = obj.optString("name", "")
            val friendPhotoUri = obj.optString("photoUri", "")

            if (uid.isNotEmpty()) {
                val oldFriend = oldContacts[uid]
                val assignedColorHex = beautifulColors[(i + 1) % beautifulColors.size]

                mapContactsList.add(MapContactSummary(
                    uid, name, assignedColorHex,
                    oldFriend?.locationName ?: "Connecting...",
                    oldFriend?.statusStr ?: "Awaiting GPS..."
                ))

                val registration = db.collection("Users").document(uid).addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                    val lat = snapshot.getDouble("currentLat")
                    val lng = snapshot.getDouble("currentLng")
                    val accuracy = snapshot.getDouble("accuracy") ?: 10.0
                    val battery = snapshot.getLong("battery")?.toInt() ?: 100
                    val isContactSOS = snapshot.getBoolean("isSOS") ?: false
                    val remoteBearing = snapshot.getDouble("bearing")?.toFloat() ?: 0f
                    val lastUpdatedMillis = snapshot.getTimestamp("lastUpdated")?.toDate()?.time ?: System.currentTimeMillis()
                    val livePhotoUri = snapshot.getString("photoUri") ?: friendPhotoUri

                    if (lat != null && lng != null && isAdded) {
                        drawContactOnMap(uid, name, lat, lng, accuracy, battery, isContactSOS, lastUpdatedMillis, livePhotoUri, remoteBearing, assignedColorHex)
                    }
                }
                activeFirebaseListeners.add(registration)
            }
        }

        mapContactsAdapter.notifyDataSetChanged()
    }

    private fun drawContactOnMap(uid: String, name: String, lat: Double, lng: Double, accuracy: Double, battery: Int, isContactSOS: Boolean, lastUpdatedMillis: Long, photoUri: String, remoteBearing: Float, assignedColorHex: String) {
        val targetLatlng = LatLng(lat, lng)

        val trackerColor = if (isContactSOS) Color.parseColor("#EF4444") else Color.parseColor(assignedColorHex)

        val timeSinceUpdate = System.currentTimeMillis() - lastUpdatedMillis
        val isOffline = timeSinceUpdate > 120_000

        if (!contactStationaryLatLngs.containsKey(uid)) {
            contactStationaryLatLngs[uid] = targetLatlng
            contactArrivalTimes[uid] = System.currentTimeMillis()
        } else {
            val distMoved = distanceBetween(contactStationaryLatLngs[uid]!!.latitude, contactStationaryLatLngs[uid]!!.longitude, lat, lng)
            if (distMoved > GEOFENCE_RADIUS_METERS) {
                contactStationaryLatLngs[uid] = targetLatlng
                contactArrivalTimes[uid] = System.currentTimeMillis()
            }
        }
        val contactDwellMillis = System.currentTimeMillis() - (contactArrivalTimes[uid] ?: System.currentTimeMillis())

        val statusString = if (isOffline) {
            val timeStr = formatDetailedTime(timeSinceUpdate)
            if (timeStr == "Just now") "Last seen just now" else "Last seen $timeStr ago"
        } else {
            val timeStr = formatDetailedTime(contactDwellMillis)
            if (timeStr == "Just now") "Just arrived" else "Since $timeStr ago"
        }

        var distanceStr = ""
        myCurrentLatLng?.let { myPos -> distanceStr = formatDistance(distanceBetween(myPos.latitude, myPos.longitude, lat, lng)) }

        val finalJsonSnippet = JSONObject().apply {
            put("uid", uid)
            put("name", name)
            put("status", statusString)
            put("distance", distanceStr)
            put("battery", battery)
            put("isSOS", isContactSOS)
        }.toString()

        val safeContext = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val arrowColorHex = if (isContactSOS) "#EF4444" else assignedColorHex
            val friendBitmap = createMarkerBitmap(safeContext, name, photoUri, arrowColorHex)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext

                val idx = mapContactsList.indexOfFirst { it.uid == uid }
                if (idx != -1) {
                    mapContactsList[idx].locationName = "Distance: $distanceStr"
                    mapContactsList[idx].statusStr = statusString
                    mapContactsList[idx].isSOS = isContactSOS
                    mapContactsAdapter.notifyItemChanged(idx)
                }

                gMap?.let { googleMap ->
                    if (!accuracyCirclesGMap.containsKey(uid)) {
                        val circle = googleMap.addCircle(CircleOptions().center(targetLatlng).radius(accuracy).fillColor(0x223B82F6).strokeWidth(0f))
                        accuracyCirclesGMap[uid] = circle
                    } else {
                        accuracyCirclesGMap[uid]?.center = targetLatlng
                        accuracyCirclesGMap[uid]?.radius = accuracy
                    }

                    if (!contactMarkersGMap.containsKey(uid)) {
                        val gMarker = googleMap.addMarker(MarkerOptions()
                            .position(targetLatlng)
                            .title(name)
                            .snippet(finalJsonSnippet)
                            .anchor(0.5f, 0.5f)
                            .infoWindowAnchor(0.5f, 0.5f)
                            .flat(true)
                            .rotation(remoteBearing)
                        )
                        if (gMarker != null) {
                            gMarker.tag = uid
                            contactMarkersGMap[uid] = gMarker
                        }
                        val gLine = googleMap.addPolyline(com.google.android.gms.maps.model.PolylineOptions().width(10f).visible(false))
                        contactLinesGMap[uid] = gLine
                    } else {
                        contactMarkersGMap[uid]?.position = targetLatlng
                        contactMarkersGMap[uid]?.snippet = finalJsonSnippet
                        contactMarkersGMap[uid]?.rotation = remoteBearing

                        if (currentlySelectedUid == uid) contactMarkersGMap[uid]?.showInfoWindow()
                    }
                    friendBitmap?.let { contactMarkersGMap[uid]?.setIcon(BitmapDescriptorFactory.fromBitmap(it)) }
                    contactLinesGMap[uid]?.color = trackerColor
                }

                updateAllTrackingLines()
            }
        }
    }

    private fun updateAllTrackingLines() {
        val currentLat = myCurrentLatLng ?: return

        if (contactMarkersGMap.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        val shouldFetchRoute = (currentTime - lastRouteFetchTime > 10000L)

        contactMarkersGMap.forEach { (uid, targetMarker) ->
            val shouldShowLine = (uid == currentlySelectedUid)

            contactLinesGMap[uid]?.isVisible = shouldShowLine

            if (!shouldShowLine) return@forEach

            val targetPos = targetMarker.position

            if (shouldFetchRoute) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val roadRoute = fetchRoadRoute(currentLat, targetPos)
                    if (roadRoute.size > 2) routeCache[uid] = roadRoute.toMutableList()

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        contactLinesGMap[uid]?.points = roadRoute
                    }
                }
            } else {
                val cachedRoute = routeCache[uid]
                if (cachedRoute != null && cachedRoute.isNotEmpty()) {
                    cachedRoute[0] = currentLat
                    cachedRoute[cachedRoute.size - 1] = targetPos
                    contactLinesGMap[uid]?.points = cachedRoute
                } else {
                    contactLinesGMap[uid]?.points = listOf(currentLat, targetPos)
                }
            }
        }

        if (shouldFetchRoute) lastRouteFetchTime = currentTime
    }

    private suspend fun fetchRoadRoute(start: LatLng, end: LatLng): List<LatLng> {
        return withContext(Dispatchers.IO) {
            try {
                val urlString = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "AISecurity/1.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val routes = jsonObject.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")
                        val routePoints = ArrayList<LatLng>()
                        for (i in 0 until coordinates.length()) {
                            val coord = coordinates.getJSONArray(i)
                            routePoints.add(LatLng(coord.getDouble(1), coord.getDouble(0)))
                        }
                        return@withContext routePoints
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            val fallback = ArrayList<LatLng>()
            fallback.add(start)
            fallback.add(end)
            return@withContext fallback
        }
    }

    private fun isDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}