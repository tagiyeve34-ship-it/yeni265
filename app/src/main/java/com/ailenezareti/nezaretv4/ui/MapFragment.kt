package com.ailenezareti.nezaretv4.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ailenezareti.nezaretv4.Prefs
import com.ailenezareti.nezaretv4.api.ApiClient
import com.ailenezareti.nezaretv4.databinding.FragmentMapBinding
import com.ailenezareti.nezaretv4.model.LocationPoint
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.*

class MapFragment : Fragment() {
    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!
    private var latestPoint: GeoPoint? = null
    private var points: List<LocationPoint> = emptyList()
    private var routeShown = false
    private var selectedDate: String? = null
    private var satellite = false

    private val esri: ITileSource = object : XYTileSource("Esri", 0, 19, 256, ".jpg", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
        override fun getTileURLString(index: Long): String {
            val z = MapTileIndex.getZoom(index); val x = MapTileIndex.getX(index); val y = MapTileIndex.getY(index)
            return "$baseUrl$z/$y/$x$mImageFilenameEnding"
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentMapBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.controller.setZoom(17.0)
        BottomSheetBehavior.from(b.bottomSheet).apply {
            peekHeight = (150 * resources.displayMetrics.density).toInt()
            isHideable = false
            isFitToContents = true
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        b.layers.setOnClickListener {
            satellite = !satellite
            b.map.setTileSource(if (satellite) esri else TileSourceFactory.MAPNIK)
            b.map.invalidate()
        }
        b.target.setOnClickListener { latestPoint?.let { b.map.controller.animateTo(it) } }
        b.refresh.setOnClickListener { load() }
        b.route.setOnClickListener {
            routeShown = !routeShown
            drawMap()
            b.route.text = if (routeShown) "Marşrutu gizlət" else "Marşrut"
        }
        b.history.setOnClickListener { pickDate() }
        b.share.setOnClickListener {
            latestPoint?.let { p ->
                val text = "${b.mapChild.text}: https://maps.google.com/?q=${p.latitude},${p.longitude}"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Mövqeyi paylaş"))
            }
        }
        load()
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            selectedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m+1, d)
            routeShown = true
            b.route.text = "Marşrutu gizlət"
            load()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun load() {
        val id = Prefs.child(requireContext()); if (id < 0) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = ApiClient.service(requireContext())
                val child = api.children().body()?.children?.firstOrNull { it.id == id }
                b.mapChild.text = child?.name ?: "Uşaq"
                b.mapAvatar.text = child?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                b.mapStatus.text = child?.last_seen?.let { "Son mövqe • $it" } ?: "Son mövqe"
                points = if (selectedDate == null) api.locations(id, "today").body()?.locations.orEmpty()
                else api.locations(id, "custom", "$selectedDate 00:00:00", "$selectedDate 23:59:59").body()?.locations.orEmpty()
                if (points.isEmpty()) { Toast.makeText(requireContext(), "Bu tarix üçün GPS yoxdur", Toast.LENGTH_SHORT).show(); return@launch }
                points = points.sortedBy { it.recorded_at }
                val last = points.last()
                latestPoint = GeoPoint(last.latitude.toDouble(), last.longitude.toDouble())
                b.mapBattery.text = last.battery_pct?.let { "▮ $it%" } ?: "—"
                b.mapAddress.text = "${"%.5f".format(last.latitude.toDouble())}, ${"%.5f".format(last.longitude.toDouble())}"
                b.mapMeta.text = "Dəqiqlik ${last.accuracy_m ?: "—"} m  •  ${last.recorded_at.takeLast(8).take(5)}"
                drawMap()
                b.map.controller.setCenter(latestPoint)
            } catch (_: Exception) { Toast.makeText(requireContext(), "Xəritə məlumatı yüklənmədi", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun drawMap() {
        if (_b == null || points.isEmpty()) return
        b.map.overlays.clear()
        var km = 0.0
        var accepted = 1
        if (routeShown && points.size > 1) {
            val good = mutableListOf<GeoPoint>()
            var prev = points.first()
            good.add(GeoPoint(prev.latitude.toDouble(), prev.longitude.toDouble()))
            for (i in 1 until points.size) {
                val cur = points[i]
                val a = GeoPoint(prev.latitude.toDouble(), prev.longitude.toDouble())
                val c = GeoPoint(cur.latitude.toDouble(), cur.longitude.toDouble())
                val d = dist(a,c)
                val sec = timeDiff(prev.recorded_at, cur.recorded_at)
                val speed = if (sec > 0) d/sec*3.6 else 0.0
                if (d <= 3000 || speed <= 180) { good.add(c); km += d/1000.0; accepted++ }
                prev = cur
            }
            if (good.size > 1) {
                b.map.overlays.add(Polyline().apply { setPoints(good); outlinePaint.color=Color.parseColor("#2478F3"); outlinePaint.strokeWidth=9f; outlinePaint.strokeCap=Paint.Cap.ROUND })
                b.map.overlays.add(Marker(b.map).apply { position=good.first(); title="Başlanğıc"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); icon=dot("#17C979") })
            }
        }
        val p = latestPoint ?: return
        b.map.overlays.add(Marker(b.map).apply { position=p; title=b.mapChild.text.toString(); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); icon=avatar(b.mapAvatar.text.toString()) })
        b.mapSummary.text = if (routeShown) "${selectedDate ?: "Bu gün"}  •  ${String.format("%.1f km", km)}  •  $accepted GPS nöqtə" else "Açıldıqda yalnız son mövqe göstərilir"
        b.map.invalidate()
    }

    private fun timeDiff(a:String,b:String):Double = try {
        val f=SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US); ((f.parse(b)?.time?:0)-(f.parse(a)?.time?:0))/1000.0
    } catch (_:Exception){0.0}

    private fun dist(a:GeoPoint,c:GeoPoint):Double {
        val r=6371000.0; val p1=Math.toRadians(a.latitude); val p2=Math.toRadians(c.latitude); val dp=Math.toRadians(c.latitude-a.latitude); val dl=Math.toRadians(c.longitude-a.longitude)
        val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2); return 2*r*atan2(sqrt(h),sqrt(1-h))
    }

    private fun avatar(letter:String):BitmapDrawable {
        val s=104; val bmp=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888); val c=Canvas(bmp)
        val white=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE}; val blue=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2478F3")}
        c.drawCircle(s/2f,s/2f-5,s/2f-5,white); c.drawCircle(s/2f,s/2f-5,s/2f-11,blue)
        val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=38f;textAlign=Paint.Align.CENTER;isFakeBoldText=true}; c.drawText(letter,s/2f,s/2f-5-(t.ascent()+t.descent())/2,t)
        val p=Path().apply{moveTo(s/2f-10,s-22f);lineTo(s/2f+10,s-22f);lineTo(s/2f,s-4f);close()}; c.drawPath(p,blue)
        return BitmapDrawable(resources,bmp)
    }

    private fun dot(hex:String):BitmapDrawable {
        val s=32; val bmp=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888); val c=Canvas(bmp); val w=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE}; val d=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor(hex)}; c.drawCircle(16f,16f,16f,w); c.drawCircle(16f,16f,11f,d); return BitmapDrawable(resources,bmp)
    }

    override fun onResume(){super.onResume();b.map.onResume()}
    override fun onPause(){b.map.onPause();super.onPause()}
    override fun onDestroyView(){super.onDestroyView();_b=null}
}
