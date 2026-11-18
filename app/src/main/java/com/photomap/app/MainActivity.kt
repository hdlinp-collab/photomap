package com.photomap.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var statusCard: View
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var permissionPanel: View
    private lateinit var grantButton: MaterialButton

    private lateinit var repository: PhotoRepository
    private lateinit var thumbs: ThumbnailLoader
    private lateinit var clusters: PhotoClusterManager

    private var photos: List<Photo> = emptyList()
    private var loadJob: Job? = null
    private var hideStatusJob: Job? = null
    private var loaded = false
    private var fittedOnce = false
    private var permanentlyDenied = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasReadAccess()) {
                loadPhotos()
            } else {
                permanentlyDenied = !shouldShowRequestPermissionRationale(mainPermission())
            }
            updatePermissionPanel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // osmdroid 設定：User-Agent 是 OpenStreetMap 圖磚伺服器要求的；快取放在 App 自己的目錄
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        permissionPanel = findViewById(R.id.permissionPanel)
        grantButton = findViewById(R.id.grantButton)

        setupMap()
        setupInsets()

        repository = PhotoRepository(applicationContext)
        thumbs = ThumbnailLoader(applicationContext, lifecycleScope)
        clusters = PhotoClusterManager(map, thumbs, ::onClusterClick)

        findViewById<FloatingActionButton>(R.id.fitFab).setOnClickListener { fitAll(animate = true) }
        findViewById<FloatingActionButton>(R.id.refreshFab).setOnClickListener {
            if (hasReadAccess()) loadPhotos() else requestPermissions()
        }
        grantButton.setOnClickListener {
            if (permanentlyDenied) openAppSettings() else requestPermissions()
        }

        if (hasReadAccess()) loadPhotos() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        // 使用者可能剛從系統設定回來並給了權限
        if (hasReadAccess() && !loaded && loadJob?.isActive != true) loadPhotos()
        updatePermissionPanel()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 2.0
        map.maxZoomLevel = 19.0
        map.isVerticalMapRepetitionEnabled = false
        map.setScrollableAreaLimitLatitude(
            MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0,
        )
        map.controller.setZoom(3.0)
        map.controller.setCenter(GeoPoint(25.0, 115.0))
    }

    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        val fabColumn = findViewById<LinearLayout>(R.id.fabColumn)
        val credit = findViewById<View>(R.id.credit)
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            statusCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + (12 * dp).toInt()
            }
            fabColumn.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + (16 * dp).toInt()
                rightMargin = bars.right + (16 * dp).toInt()
            }
            credit.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + (4 * dp).toInt()
                leftMargin = bars.left + (4 * dp).toInt()
            }
            insets
        }
    }

    // ---------- 權限 ----------

    private fun mainPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasReadAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requestPermissions() = permissionLauncher.launch(requiredPermissions())

    private fun updatePermissionPanel() {
        val need = !hasReadAccess()
        permissionPanel.visibility = if (need) View.VISIBLE else View.GONE
        grantButton.setText(if (permanentlyDenied) R.string.open_settings else R.string.grant)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    // ---------- 載入相片 ----------

    private fun loadPhotos() {
        loadJob?.cancel()
        hideStatusJob?.cancel()
        updatePermissionPanel()
        showStatus(getString(R.string.scanning), busy = true)
        loadJob = lifecycleScope.launch {
            repository.scan().collect { state ->
                photos = state.photos
                clusters.setPhotos(state.photos)
                if (!state.done) {
                    showStatus(
                        getString(R.string.scan_progress, state.scanned, state.total, state.photos.size),
                        busy = true,
                    )
                } else {
                    loaded = true
                    if (state.photos.isEmpty()) {
                        showStatus(getString(R.string.scan_none), busy = false)
                    } else {
                        showStatus(getString(R.string.scan_done, state.photos.size), busy = false)
                        if (!fittedOnce) {
                            fittedOnce = true
                            fitAll(animate = false)
                        }
                        hideStatusJob = launch {
                            delay(3000)
                            statusCard.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun showStatus(text: String, busy: Boolean) {
        statusCard.visibility = View.VISIBLE
        statusText.text = text
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ---------- 地圖操作 ----------

    /** 把地圖縮放到可以看到所有相片。 */
    private fun fitAll(animate: Boolean) {
        if (photos.isEmpty()) return
        if (map.width == 0 || map.height == 0) {
            map.post { fitAll(animate) }
            return
        }
        var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
        for (p in photos) {
            if (p.lat > n) n = p.lat
            if (p.lat < s) s = p.lat
            if (p.lon > e) e = p.lon
            if (p.lon < w) w = p.lon
        }
        if (n - s < 0.002 && e - w < 0.002) {
            val center = GeoPoint((n + s) / 2, (e + w) / 2)
            if (animate) map.controller.animateTo(center, 16.0, 800L)
            else { map.controller.setZoom(16.0); map.controller.setCenter(center) }
            return
        }
        zoomTo(n, s, e, w, animate)
    }

    private fun zoomTo(n: Double, s: Double, e: Double, w: Double, animate: Boolean) {
        val maxLat = MapView.getTileSystem().maxLatitude
        val padLat = (n - s) * 0.1
        val padLon = (e - w) * 0.1
        val box = BoundingBox(
            (n + padLat).coerceAtMost(maxLat),
            (e + padLon).coerceAtMost(180.0),
            (s - padLat).coerceAtLeast(-maxLat),
            (w - padLon).coerceAtLeast(-180.0),
        )
        val border = (48 * resources.displayMetrics.density).toInt()
        map.zoomToBoundingBox(box, animate, border)
    }

    private fun onClusterClick(cluster: PhotoCluster) {
        if (cluster.photos.size == 1) {
            openPhoto(cluster.cover)
            return
        }
        var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
        for (p in cluster.photos) {
            if (p.lat > n) n = p.lat
            if (p.lat < s) s = p.lat
            if (p.lon > e) e = p.lon
            if (p.lon < w) w = p.lon
        }
        val tiny = n - s < 0.0003 && e - w < 0.0003
        if (tiny || map.zoomLevelDouble >= map.maxZoomLevel - 0.5) {
            showPhotoGrid(cluster.photos)
        } else {
            zoomTo(n, s, e, w, animate = true)
        }
    }

    private fun showPhotoGrid(list: List<Photo>) {
        val dialog = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt())
        }
        val title = TextView(this).apply {
            text = getString(R.string.photos_count, list.size)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding((4 * dp).toInt(), 0, 0, (12 * dp).toInt())
        }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = PhotoGridAdapter(list, thumbs) { openPhoto(it) }
        }
        container.addView(title)
        container.addView(grid)
        dialog.setContentView(container)
        dialog.show()
    }

    private fun openPhoto(photo: Photo) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(photo.uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }
}
