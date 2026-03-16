package com.example.aisecurity.ui.map

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import com.example.aisecurity.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import com.google.android.material.button.MaterialButton

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

class MapFragment : Fragment(), SensorEventListener, OnMapReadyCallback {

    private lateinit var map: MapView
    private lateinit var googleMapContainer: View
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var btnSettings: MaterialButton

    private lateinit var targetPoint: GeoPoint
    private lateinit var gTargetLatLng: LatLng

    private val routingLine = Polyline()
    private lateinit var myLocationMarker: Marker

    private var gMap: GoogleMap? = null
    private var gMapMarker: com.google.android.gms.maps.model.Marker? = null
    private var gMapPolyline: com.google.android.gms.maps.model.Polyline? = null

    private lateinit var gpsProvider: GpsMyLocationProvider
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

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

        map = view.findViewById(R.id.mapView)
        googleMapContainer = view.findViewById(R.id.googleMapContainer)
        tvLocationStatus = view.findViewById(R.id.tvLocationStatus)
        tvNetworkStatus = view.findViewById(R.id.tvNetworkStatus)
        btnSettings = view.findViewById(R.id.btnSettings)

        // --- FIXED: Use HIDE and ADD so the map is preserved in the backstack! ---
        btnSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this@MapFragment)
                .add(R.id.fragment_container, com.example.aisecurity.ui.settings.TrustedContactsFragment())
                .addToBackStack("TrustedContacts")
                .commit()
        }

        val prefs = requireActivity().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val targetLat = prefs.getFloat("target_lat", 10.3157f).toDouble()
        val targetLng = prefs.getFloat("target_lng", 123.8854f).toDouble()

        targetPoint = GeoPoint(targetLat, targetLng)
        gTargetLatLng = LatLng(targetLat, targetLng)

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
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun switchToOnlineMap() {
        if (googleMapContainer.visibility == View.VISIBLE) return
        tvNetworkStatus.text = "ONLINE"
        tvNetworkStatus.setTextColor(Color.parseColor("#4CAF50"))
        map.visibility = View.GONE
        googleMapContainer.visibility = View.VISIBLE
        val mapFragment = childFragmentManager.findFragmentById(R.id.googleMapContainer) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun switchToOfflineMap() {
        if (map.visibility == View.VISIBLE) return
        tvNetworkStatus.text = "OFFLINE"
        tvNetworkStatus.setTextColor(Color.parseColor("#FF9800"))
        googleMapContainer.visibility = View.GONE
        map.visibility = View.VISIBLE
    }

    override fun onMapReady(googleMap: GoogleMap) {
        if (gMap != null) return
        gMap = googleMap

        try {
            gMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark))
        } catch (e: Exception) { e.printStackTrace() }

        gMap?.addMarker(MarkerOptions().position(gTargetLatLng).title("Stolen Phone"))
        gMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(gTargetLatLng, 15f))

        gMapPolyline = gMap?.addPolyline(PolylineOptions()
            .color(Color.parseColor("#2196F3"))
            .width(12f)
            .geodesic(false))

        val customArrow = ContextCompat.getDrawable(requireContext(), R.drawable.ic_nav_arrow)?.toBitmap(120, 120)
        if (customArrow != null) {
            gMapMarker = gMap?.addMarker(MarkerOptions()
                .position(gTargetLatLng)
                .icon(BitmapDescriptorFactory.fromBitmap(customArrow))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .visible(false))
        }
    }

    private fun setupOfflineMap() {
        val darkTileSource = object : OnlineTileSourceBase(
            "CartoDbDark", 1, 20, 256, ".png",
            arrayOf("https://cartodb-basemaps-a.global.ssl.fastly.net/dark_all/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
            }
        }
        map.setTileSource(darkTileSource)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(targetPoint)

        val targetMarker = Marker(map)
        targetMarker.position = targetPoint
        targetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        targetMarker.title = "Stolen Phone"
        map.overlays.add(targetMarker)

        routingLine.outlinePaint.color = Color.parseColor("#2196F3")
        routingLine.outlinePaint.strokeWidth = 12f
        map.overlays.add(routingLine)

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

        gpsProvider.startLocationProvider { location, _ ->
            if (location != null && isAdded) {
                val myPoint = GeoPoint(location.latitude, location.longitude)
                val gMyLatLng = LatLng(location.latitude, location.longitude)

                activity?.runOnUiThread {
                    myLocationMarker.position = myPoint
                    routingLine.setPoints(listOf(myPoint, targetPoint))
                    map.invalidate()

                    gMapMarker?.position = gMyLatLng
                    gMapMarker?.isVisible = true
                    gMapPolyline?.points = listOf(gMyLatLng, gTargetLatLng)

                    val distanceInMeters = myPoint.distanceToAsDouble(targetPoint)
                    if (distanceInMeters > 1000) {
                        tvLocationStatus.text = "Distance to phone: ${String.format("%.1f", distanceInMeters / 1000)} km"
                    } else {
                        tvLocationStatus.text = "Distance to phone: ${String.format("%.0f", distanceInMeters)} meters"
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
}