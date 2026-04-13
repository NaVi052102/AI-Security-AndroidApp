package com.example.aisecurity.ui.map

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.aisecurity.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
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
import java.util.Locale

class MapFragment : Fragment(), SensorEventListener, OnMapReadyCallback {

    // Firebase Engine
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
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
            override fun onAvailable(network: Network) {
                activity?.runOnUiThread { switchToOnlineMap() }
            }
            override fun onLost(network: Network) {
                activity?.runOnUiThread { switchToOfflineMap() }
            }
        }

        gpsProvider = GpsMyLocationProvider(requireContext())

        // --- HARDWARE OVERRIDE: FORCE MAXIMUM SPEED ---
        // 0 time delay, 0 meter distance delay. This pushes the GPS chip to its absolute limit.
        gpsProvider.locationUpdateMinTime = 0
        gpsProvider.locationUpdateMinDistance = 0f

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun switchToOnlineMap() {
        if (googleMapContainer.visibility == View.VISIBLE) return

        tvNetworkStatus.text = "● ONLINE"
        tvNetworkStatus.setTextColor(Color.parseColor("#34C759"))
        tvNetworkStatus.setBackgroundColor(Color.parseColor("#1A34C759"))

        map.visibility = View.GONE
        googleMapContainer.visibility = View.VISIBLE
        val mapFragment = childFragmentManager.findFragmentById(R.id.googleMapContainer) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun switchToOfflineMap() {
        if (map.visibility == View.VISIBLE) return

        tvNetworkStatus.text = "● OFFLINE"
        tvNetworkStatus.setTextColor(Color.parseColor("#FF9500"))
        tvNetworkStatus.setBackgroundColor(Color.parseColor("#1AFF9500"))

        googleMapContainer.visibility = View.GONE
        map.visibility = View.VISIBLE
    }

    override fun onMapReady(googleMap: GoogleMap) {
        if (gMap != null) return
        gMap = googleMap

        if (isDarkMode()) {
            try {
                gMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark))
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            gMap?.setMapStyle(null)
        }

        val customArrow = ContextCompat.getDrawable(requireContext(), R.drawable.ic_nav_arrow)?.toBitmap(120, 120)
        if (customArrow != null) {
            gMapMarker = gMap?.addMarker(MarkerOptions()
                .position(LatLng(0.0, 0.0))
                .icon(BitmapDescriptorFactory.fromBitmap(customArrow))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .visible(false))
        }
    }

    private fun setupOfflineMap() {
        val tileUrl = if (isDarkMode()) {
            "https://cartodb-basemaps-a.global.ssl.fastly.net/dark_all/"
        } else {
            "https://cartodb-basemaps-a.global.ssl.fastly.net/light_all/"
        }

        val dynamicTileSource = object : OnlineTileSourceBase(
            "CartoDbDynamic", 1, 20, 256, ".png",
            arrayOf(tileUrl)
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
            }
        }
        map.setTileSource(dynamicTileSource)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)

        myLocationMarker = Marker(map)
        val customArrow = ContextCompat.getDrawable(requireContext(), R.drawable.ic_nav_arrow)?.toBitmap(120, 120)
        if (customArrow != null) {
            myLocationMarker.icon = BitmapDrawable(resources, customArrow)
        }
        myLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        myLocationMarker.infoWindow = null
        myLocationMarker.isFlat = true
        map.overlays.add(myLocationMarker)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        if (isInternetAvailable(requireContext())) switchToOnlineMap() else switchToOfflineMap()

        // ========================================================
        // REAL-TIME GPS TRACKING, GEOCODING & FIREBASE UPLOAD
        // ========================================================
        gpsProvider.startLocationProvider { location, _ ->
            if (location != null && isAdded) {

                val currentLat = location.latitude
                val currentLng = location.longitude

                val myPoint = GeoPoint(currentLat, currentLng)
                val gMyLatLng = LatLng(currentLat, currentLng)

                // Run the Heavy Geocoder in a Background Thread so the map doesn't freeze
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var placeName = "Tracking Location..."
                    try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        val addresses = geocoder.getFromLocation(currentLat, currentLng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            // Grabs the full street address (e.g., "123 Osmeña Blvd, Cebu City")
                            placeName = addresses[0].getAddressLine(0) ?: "Unknown Street"
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 1. UPDATE FIREBASE REAL-TIME
                    val userId = auth.currentUser?.uid
                    if (userId != null) {
                        val locationData = hashMapOf(
                            "currentLat" to currentLat,
                            "currentLng" to currentLng,
                            "placeName" to placeName,
                            "lastUpdated" to com.google.firebase.Timestamp.now()
                        )

                        db.collection("Users").document(userId)
                            .set(locationData, SetOptions.merge())
                    }

                    // 2. UPDATE UI (Must be back on the Main Thread)
                    withContext(Dispatchers.Main) {
                        myLocationMarker.position = myPoint
                        map.invalidate()

                        gMapMarker?.position = gMyLatLng
                        gMapMarker?.isVisible = true

                        if (isFirstLocationUpdate) {
                            map.controller.setCenter(myPoint)
                            gMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(gMyLatLng, 18f))
                            isFirstLocationUpdate = false
                        }

                        // Display the actual street name on the screen
                        tvLocationStatus.text = placeName
                    }
                }
            }
        }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        gpsProvider.stopLocationProvider()
        sensorManager.unregisterListener(this)
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val adjustedMatrix = FloatArray(9)
            val windowManager = requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, adjustedMatrix)
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedMatrix)
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjustedMatrix)
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedMatrix)
                else -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, adjustedMatrix)
            }
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(adjustedMatrix, orientationAngles)
            var azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuthDeg < 0) azimuthDeg += 360f
            myLocationMarker.rotation = azimuthDeg
            gMapMarker?.rotation = azimuthDeg
            map.invalidate()
        }
    }

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