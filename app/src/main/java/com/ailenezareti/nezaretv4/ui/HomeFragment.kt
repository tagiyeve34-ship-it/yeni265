package com.ailenezareti.nezaretv4.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ailenezareti.nezaretv4.Prefs
import com.ailenezareti.nezaretv4.R
import com.ailenezareti.nezaretv4.api.ApiClient
import com.ailenezareti.nezaretv4.databinding.FragmentHomeBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import kotlin.math.*

class HomeFragment : Fragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        b.shortcutMap.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_map) }
        b.shortcutCalls.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_calls) }
        b.shortcutZones.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_zones) }
        b.shortcutAlerts.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_more) }
        b.bellBtn.setOnClickListener { (activity as? MainActivity)?.navigate(R.id.nav_more) }
        load()
    }

    private fun distance(a: GeoPoint, c: GeoPoint): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(c.latitude)
        val dp = Math.toRadians(c.latitude - a.latitude); val dl = Math.toRadians(c.longitude - a.longitude)
        val h = sin(dp/2).pow(2) + cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*r*atan2(sqrt(h), sqrt(1-h))
    }

    private fun load() {
        val id = Prefs.child(requireContext())
        if (id < 0) { viewLifecycleOwner.lifecycleScope.launch { delay(700); if (_b != null) load() }; return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val api = ApiClient.service(requireContext())
                val children = api.children().body()?.children.orEmpty()
                val child = children.firstOrNull { it.id == id } ?: children.firstOrNull()
                b.childName.text = child?.name ?: "Uşaq"
                b.avatar.text = child?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                b.lastSeen.text = child?.last_seen?.let { "Son görülmə: $it" } ?: "Son görülmə gözlənilir"

                val loc = api.locations(id, "today").body()?.locations.orEmpty()
                val latest = loc.lastOrNull()
                b.todayPoints.text = loc.size.toString()
                b.batteryTop.text = latest?.battery_pct?.let { "▮ $it%" } ?: "—"
                val battery = latest?.battery_pct ?: 0
                b.batteryAnalysis.text = latest?.battery_pct?.let { "$it%" } ?: "—%"
                b.batteryProgress.progress = battery.coerceIn(0, 100)
                b.batteryAnalysisSub.text = when { battery == 0 -> "Məlumat yoxdur"; battery <= 20 -> "Aşağı səviyyə"; battery <= 50 -> "Orta səviyyə"; else -> "Normal səviyyə" }
                b.activityLocation.text = latest?.let { "●  Mövqe yeniləndi  •  ${it.recorded_at.takeLast(8).take(5)}" } ?: "●  Mövqe məlumatı yoxdur"
                var meters = 0.0
                for (i in 1 until loc.size) {
                    val a = GeoPoint(loc[i-1].latitude.toDouble(), loc[i-1].longitude.toDouble())
                    val c = GeoPoint(loc[i].latitude.toDouble(), loc[i].longitude.toDouble())
                    val d = distance(a,c)
                    if (d < 3000) meters += d
                }
                b.todayDistance.text = String.format("%.1f km", meters/1000.0)

                val today = java.time.LocalDate.now().toString()
                val calls = api.calls(id, today, today, "all", null, 500, 0).body()?.calls.orEmpty()
                b.todayCalls.text = calls.size.toString()
                val incoming = calls.count { it.call_type.equals("incoming", true) || it.call_type == "1" }
                val outgoing = calls.count { it.call_type.equals("outgoing", true) || it.call_type == "2" }
                val missed = calls.count { it.call_type.equals("missed", true) || it.call_type == "3" }
                b.callAnalysisTotal.text = "${calls.size} zəng"
                b.incomingCount.text = incoming.toString()
                b.outgoingCount.text = outgoing.toString()
                b.missedCount.text = missed.toString()
                val latestCall = calls.firstOrNull()
                b.activityCall.text = latestCall?.let { "☎  ${it.contact_name?.takeIf { n -> n.isNotBlank() } ?: it.phone_number}  •  ${it.occurred_at.takeLast(8).take(5)}" } ?: "☎  Son zəng yoxdur"

                val zones = api.zones(id).body()?.zones.orEmpty()
                val activeZones = zones.count { it.is_active == 1 }
                b.zoneAnalysis.text = "$activeZones aktiv"
                b.zoneAnalysisSub.text = if (zones.isEmpty()) "Zona yaradılmayıb" else "Cəmi ${zones.size} zona • ${zones.size-activeZones} deaktiv"

                val alerts = api.alerts(id).body()?.alerts.orEmpty()
                val zone = alerts.firstOrNull { it.alert_type.contains("zone", true) }
                b.activityZone.text = zone?.let { "⌖  ${it.message}" } ?: "⌖  Zona bildirişi yoxdur"
            } catch (_: Exception) {}
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
