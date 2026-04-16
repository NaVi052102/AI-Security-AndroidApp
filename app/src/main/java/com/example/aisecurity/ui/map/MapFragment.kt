package com.example.aisecurity.ui.map

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MapFragment : Fragment(), SensorEventListener, OnMapReadyCallback {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var map: MapView
    private lateinit var googleMapContainer: View
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var btnSettings: Button

    private lateinit var myLocationMarker: Marker
    private var gMap: GoogleMap? = null
    private var gMapMarker: com.google.android.gms.maps.model.Marker? = null

    private lateinit var gpsProvider: GpsMyLocationProvider
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private var isFirstLocationUpdate = true
    private var myCurrentGeoPoint: GeoPoint? = null
    private var myCurrentLatLng: LatLng? = null

    private var myName: String = "Me"
    private var myPhotoUri: String = ""
    private var cachedMyBitmap: android.graphics.Bitmap? = null

    private val activeFirebaseListeners = mutableListOf<ListenerRegistration>()
    private val contactMarkersOSM = mutableMapOf<String, Marker>()
    private val contactLinesOSM = mutableMapOf<String, Polyline>()
    private val contactMarkersGMap = mutableMapOf<String, com.google.android.gms.maps.model.Marker>()
    private val contactLinesGMap = mutableMapOf<String, com.google.android.gms.maps.model.Polyline>()

    private var lastRouteFetchTime = 0L
    private val routeCache = mutableMapOf<String, MutableList<GeoPoint>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ctx = requireActivity().applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        map = view.findViewById(R.id.mapView)
        googleMapContainer = view.findViewById(R.id.googleMapContainer)
        tvLocationStatus = view.findViewById(R.id.tvLocationStatus)
        tvNetworkStatus = view.findViewById(R.id.tvNetworkStatus)
        btnSettings = view.findViewById(R.id.btnSettings)

        val bottomControlCard = view.findViewById<LinearLayout>(R.id.bottomControlCard)
        val tvSecureTrackingTitle = view.findViewById<TextView>(R.id.tvSecureTrackingTitle)

        val isNightMode = isDarkMode()
        if (!isNightMode) {
            val cardBg = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.parseColor("#F5FFFFFF"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
            bottomControlCard.background = cardBg
            tvSecureTrackingTitle.setTextColor(Color.parseColor("#0F172A"))
            tvLocationStatus.setTextColor(Color.parseColor("#334155"))
        } else {
            val cardBg = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.parseColor("#E60F172A"))
                setStroke(2, Color.parseColor("#334155"))
            }
            bottomControlCard.background = cardBg
            tvSecureTrackingTitle.setTextColor(Color.parseColor("#F8FAFC"))
            tvLocationStatus.setTextColor(Color.parseColor("#94A3B8"))
        }

        applyGlassButton(btnSettings, isNightMode)

        // 🚨 THE FIX: Revert to .hide() to prevent the Google Maps Backstack crash
        btnSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this@MapFragment)
                .add(R.id.fragment_container, com.example.aisecurity.ui.settings.TrustedContactsFragment())
                .addToBackStack("TrustedContacts")
                .commit()
        }

        setupOfflineMap()

        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { activity?.runOnUiThread { switchToOnlineMap() } }
            override fun onLost(network: Network) { activity?.runOnUiThread { switchToOfflineMap() } }
        }

        gpsProvider = GpsMyLocationProvider(requireContext())
        gpsProvider.locationUpdateMinTime = 0
        gpsProvider.locationUpdateMinDistance = 0f

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    // 🚨 THE FIX: Manage battery & sensors when the fragment is hidden/shown
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // Fragment is hidden (Trusted Contacts open). Pause all heavy tasks.
            map.onPause()
            gpsProvider.stopLocationProvider()
            sensorManager.unregisterListener(this)
            activeFirebaseListeners.forEach { it.remove() }
            activeFirebaseListeners.clear()
        } else {
            // Fragment is visible again (Back button pressed). Resume everything!
            map.onResume()
            lastRouteFetchTime = 0L

            val userId = auth.currentUser?.uid
            if (userId != null) {
                startLocationTracking(userId)
            }
            rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            initLife360Engine()
        }
    }

    private fun createMarkerBitmap(context: Context, name: String, photoUri: String): android.graphics.Bitmap? {
        try {
            val size = (60 * context.resources.displayMetrics.density).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val center = size / 2f
            val radius = size / 2f

            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(center, center, radius, paint)

            val innerRadius = radius - (4 * context.resources.displayMetrics.density)
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
                } catch (e: Exception) {
                    Log.e("AVATAR_ERROR", "Failed to load URI due to Android permissions: ${e.message}")
                }
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
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun getInitials(name: String): String {
        return name.trim().split("\\s+".toRegex())
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
    }

    private fun applyGlassButton(button: Button, isNightMode: Boolean) {
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

    private fun switchToOnlineMap() {
        if (googleMapContainer.visibility == View.VISIBLE) return
        tvNetworkStatus.text = "ONLINE"
        tvNetworkStatus.setTextColor(Color.parseColor("#10B981"))
        val badgeBg = GradientDrawable().apply { cornerRadius = 50f; setColor(Color.parseColor("#1A10B981")) }
        tvNetworkStatus.background = badgeBg
        map.visibility = View.GONE
        googleMapContainer.visibility = View.VISIBLE
        val mapFragment = childFragmentManager.findFragmentById(R.id.googleMapContainer) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun switchToOfflineMap() {
        if (map.visibility == View.VISIBLE) return
        tvNetworkStatus.text = "OFFLINE"
        tvNetworkStatus.setTextColor(Color.parseColor("#F59E0B"))
        val badgeBg = GradientDrawable().apply { cornerRadius = 50f; setColor(Color.parseColor("#1AF59E0B")) }
        tvNetworkStatus.background = badgeBg
        googleMapContainer.visibility = View.GONE
        map.visibility = View.VISIBLE
    }

    override fun onMapReady(googleMap: GoogleMap) {
        if (gMap != null) return
        gMap = googleMap
        if (isDarkMode()) {
            try { gMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark)) } catch (e: Exception) {}
        }

        gMapMarker = gMap?.addMarker(MarkerOptions()
            .position(LatLng(0.0, 0.0))
            .anchor(0.5f, 0.5f).flat(true).visible(false))

        updateAllTrackingLines()
    }

    private fun setupOfflineMap() {
        val tileUrl = if (isDarkMode()) "https://cartodb-basemaps-a.global.ssl.fastly.net/dark_all/" else "https://cartodb-basemaps-a.global.ssl.fastly.net/light_all/"
        val dynamicTileSource = object : OnlineTileSourceBase("CartoDbDynamic", 1, 20, 256, ".png", arrayOf(tileUrl)) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
            }
        }
        map.setTileSource(dynamicTileSource)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)

        myLocationMarker = Marker(map)
        myLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        myLocationMarker.infoWindow = null
        myLocationMarker.isFlat = true
        map.overlays.add(myLocationMarker)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()

        lastRouteFetchTime = 0L

        val networkRequest = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        if (isInternetAvailable(requireContext())) switchToOnlineMap() else switchToOfflineMap()

        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("Users").document(userId).get().addOnSuccessListener { doc ->
                if (doc != null && doc.exists() && isAdded) {
                    myName = doc.getString("fullName") ?: "Me"
                    myPhotoUri = doc.getString("photoUri") ?: ""

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        cachedMyBitmap = createMarkerBitmap(requireContext(), myName, myPhotoUri)
                        withContext(Dispatchers.Main) { startLocationTracking(userId) }
                    }
                } else {
                    startLocationTracking(userId)
                }
            }.addOnFailureListener { startLocationTracking(userId) }
        }

        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (!isHidden) initLife360Engine()
    }

    private fun startLocationTracking(userId: String) {
        gpsProvider.startLocationProvider { location, _ ->
            if (location != null && isAdded) {
                val currentLat = location.latitude
                val currentLng = location.longitude

                val currentGeo = GeoPoint(currentLat, currentLng)
                val currentLatlng = LatLng(currentLat, currentLng)

                myCurrentGeoPoint = currentGeo
                myCurrentLatLng = currentLatlng

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var placeName = "Tracking Location..."
                    try {
                        val addresses = Geocoder(requireContext(), Locale.getDefault()).getFromLocation(currentLat, currentLng, 1)
                        if (!addresses.isNullOrEmpty()) placeName = addresses[0].getAddressLine(0) ?: "Unknown Street"
                    } catch (e: Exception) { }

                    val locationData = hashMapOf("currentLat" to currentLat, "currentLng" to currentLng, "placeName" to placeName, "lastUpdated" to com.google.firebase.Timestamp.now())
                    db.collection("Users").document(userId).set(locationData, SetOptions.merge())

                    withContext(Dispatchers.Main) {

                        cachedMyBitmap?.let {
                            myLocationMarker.icon = BitmapDrawable(resources, it)
                            gMapMarker?.setIcon(BitmapDescriptorFactory.fromBitmap(it))
                        }

                        myLocationMarker.position = currentGeo
                        gMapMarker?.position = currentLatlng
                        gMapMarker?.isVisible = true

                        updateAllTrackingLines()

                        if (isFirstLocationUpdate) {
                            map.controller.setCenter(currentGeo)
                            gMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatlng, 18f))
                            isFirstLocationUpdate = false
                            initLife360Engine()
                        }
                        map.invalidate()
                        tvLocationStatus.text = placeName
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        gpsProvider.stopLocationProvider()
        sensorManager.unregisterListener(this)
        activeFirebaseListeners.forEach { it.remove() }
        activeFirebaseListeners.clear()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
    }

    // 🚨 Safely clear the Google Map if the fragment is ever actually destroyed
    override fun onDestroyView() {
        super.onDestroyView()
        gMap?.clear()
        gMap = null
    }

    private fun initLife360Engine() {
        // 1. Stop all background Firebase listeners
        activeFirebaseListeners.forEach { it.remove() }
        activeFirebaseListeners.clear()

        // 🚨 THE FIX: Erase all "Ghost" markers and lines from both maps!
        contactMarkersOSM.values.forEach { map.overlays.remove(it) }
        contactLinesOSM.values.forEach { map.overlays.remove(it) }
        contactMarkersOSM.clear()
        contactLinesOSM.clear()

        contactMarkersGMap.values.forEach { it.remove() }
        contactLinesGMap.values.forEach { it.remove() }
        contactMarkersGMap.clear()
        contactLinesGMap.clear()

        routeCache.clear()
        map.invalidate()

        // 2. Rebuild the live-tracking engine with the updated Contact List
        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("trusted_contacts_json", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val uid = if (obj.has("uid")) obj.getString("uid") else ""
                val name = obj.getString("name")
                val friendPhotoUri = if (obj.has("photoUri")) obj.getString("photoUri") else ""

                if (uid.isNotEmpty()) {
                    val registration = db.collection("Users").document(uid).addSnapshotListener { snapshot, e ->
                        if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                        val lat = snapshot.getDouble("currentLat")
                        val lng = snapshot.getDouble("currentLng")

                        val livePhotoUri = snapshot.getString("photoUri") ?: friendPhotoUri

                        if (lat != null && lng != null && isAdded) {
                            drawContactOnMap(uid, name, lat, lng, livePhotoUri)
                        }
                    }
                    activeFirebaseListeners.add(registration)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun drawContactOnMap(uid: String, name: String, lat: Double, lng: Double, photoUri: String) {
        val targetGeo = GeoPoint(lat, lng)
        val targetLatlng = LatLng(lat, lng)
        val trackerColor = Color.parseColor("#3B82F6")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val friendBitmap = createMarkerBitmap(requireContext(), name, photoUri)

            withContext(Dispatchers.Main) {
                if (!contactMarkersOSM.containsKey(uid)) {
                    val contactMarker = Marker(map).apply {
                        title = name
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    map.overlays.add(contactMarker)
                    contactMarkersOSM[uid] = contactMarker

                    val trackerLine = Polyline().apply { outlinePaint.color = trackerColor; outlinePaint.strokeWidth = 10f }
                    map.overlays.add(trackerLine)
                    contactLinesOSM[uid] = trackerLine
                }

                contactMarkersOSM[uid]?.position = targetGeo
                friendBitmap?.let { contactMarkersOSM[uid]?.icon = BitmapDrawable(resources, it) }

                gMap?.let { googleMap ->
                    if (!contactMarkersGMap.containsKey(uid)) {
                        val gMarker = googleMap.addMarker(MarkerOptions().position(targetLatlng).title(name))
                        if (gMarker != null) contactMarkersGMap[uid] = gMarker
                        val gLine = googleMap.addPolyline(com.google.android.gms.maps.model.PolylineOptions().color(trackerColor).width(10f))
                        contactLinesGMap[uid] = gLine
                    } else {
                        contactMarkersGMap[uid]?.position = targetLatlng
                    }
                    friendBitmap?.let { contactMarkersGMap[uid]?.setIcon(BitmapDescriptorFactory.fromBitmap(it)) }
                }

                updateAllTrackingLines()
            }
        }
    }

    private fun updateAllTrackingLines() {
        val currentGeo = myCurrentGeoPoint ?: return
        val currentLat = myCurrentLatLng ?: return

        if (contactMarkersOSM.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        val shouldFetchRoute = (currentTime - lastRouteFetchTime > 10000L)

        contactMarkersOSM.forEach { (uid, targetMarker) ->
            val targetPos = GeoPoint(targetMarker.position.latitude, targetMarker.position.longitude)

            if (shouldFetchRoute) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val roadRoute = fetchRoadRoute(currentGeo, targetPos)
                    if (roadRoute.size > 2) routeCache[uid] = roadRoute.toMutableList()

                    withContext(Dispatchers.Main) {
                        contactLinesOSM[uid]?.setPoints(roadRoute)
                        contactLinesGMap[uid]?.points = roadRoute.map { LatLng(it.latitude, it.longitude) }
                        map.invalidate()
                    }
                }
            } else {
                val cachedRoute = routeCache[uid]
                if (cachedRoute != null && cachedRoute.isNotEmpty()) {
                    cachedRoute[0] = currentGeo
                    cachedRoute[cachedRoute.size - 1] = targetPos

                    contactLinesOSM[uid]?.setPoints(cachedRoute)
                    contactLinesGMap[uid]?.points = cachedRoute.map { LatLng(it.latitude, it.longitude) }
                } else {
                    val points = ArrayList<GeoPoint>().apply { add(currentGeo); add(targetPos) }
                    contactLinesOSM[uid]?.setPoints(points)
                    contactLinesGMap[uid]?.points = listOf(currentLat, LatLng(targetPos.latitude, targetPos.longitude))
                }
            }
        }

        if (shouldFetchRoute) lastRouteFetchTime = currentTime
    }

    private suspend fun fetchRoadRoute(start: GeoPoint, end: GeoPoint): List<GeoPoint> {
        return withContext(Dispatchers.IO) {
            try {
                val urlString = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "AISecurity/1.0 (Android)")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val routes = jsonObject.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")

                        val routePoints = ArrayList<GeoPoint>()
                        for (i in 0 until coordinates.length()) {
                            val coord = coordinates.getJSONArray(i)
                            val lon = coord.getDouble(0)
                            val lat = coord.getDouble(1)
                            routePoints.add(GeoPoint(lat, lon))
                        }
                        return@withContext routePoints
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val fallback = ArrayList<GeoPoint>()
            fallback.add(start)
            fallback.add(end)
            return@withContext fallback
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun isInternetAvailable(context: Context): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun isDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}