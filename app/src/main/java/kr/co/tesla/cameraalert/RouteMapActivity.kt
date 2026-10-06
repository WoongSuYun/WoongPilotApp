package kr.co.tesla.cameraalert

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import kr.co.tesla.cameraalert.route.RouteLedger
import kr.co.tesla.cameraalert.route.RouteRecord

/** Native Kakao map: Korean map labels and an overlay made from the exact recorded GPS points. */
class RouteMapActivity : AppCompatActivity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val routeIds = intent.getLongArrayExtra(EXTRA_STARTED_ATS)?.toList()
            ?: listOf(intent.getLongExtra(EXTRA_STARTED_AT, -1L))
        // Keep the merged card's chronological route order.  RouteLedger itself is displayed
        // newest-first, which would otherwise make the colour assignment appear arbitrary.
        val routesByStart = RouteLedger.records(this).associateBy { it.startedAt }
        val routes = routeIds.distinct().mapNotNull(routesByStart::get)
        if (routes.isEmpty()) { finish(); return }
        title = "주행 경로 지도"
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        val points = routes.flatMap { route -> route.points.map { LatLng.from(it.latitude, it.longitude) } }
        mapView = MapView(this)
        setContentView(mapView)
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() = Unit
            override fun onMapError(error: Exception) = Unit
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                val routeColors = listOf(
                    Color.rgb(0, 168, 107),  // green
                    Color.rgb(42, 130, 228), // blue
                    Color.rgb(242, 142, 43), // orange
                    Color.rgb(156, 91, 204), // purple
                    Color.rgb(224, 82, 99)   // red
                )
                // A RouteLineSegment refers to a style by its index in this one shared set.
                val styles = RouteLineStylesSet.from(*routeColors.map { color ->
                    RouteLineStyles.from(RouteLineStyle.from(14f, color, 3f, Color.WHITE))
                }.toTypedArray())
                // RouteLineOptions without an explicit ID replaces the previous line on the
                // default layer.  Put every merged-card route into one RouteLine as segments.
                val segments = routes.flatMapIndexed { index, route ->
                    drawableSegments(route).map { routePoints ->
                        RouteLineSegment.from(routePoints).setStyles(styles.getStyles(index % routeColors.size))
                    }
                }
                if (segments.isNotEmpty()) {
                    kakaoMap.routeLineManager?.layer?.addRouteLine(
                        RouteLineOptions.from(segments).setStylesSet(styles)
                    )
                }
                kakaoMap.moveCamera(CameraUpdateFactory.fitMapPoints(points.toTypedArray(), 72))
            }
        })
    }

    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.resume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.pause(); super.onPause() }
    override fun onDestroy() { if (::mapView.isInitialized) mapView.finish(); super.onDestroy() }

    /** Do not draw a straight line across a period without trustworthy GPS positions. */
    private fun drawableSegments(route: RouteRecord): List<List<LatLng>> {
        val segments = mutableListOf<List<LatLng>>()
        val current = mutableListOf<LatLng>()
        route.points.forEach { point ->
            if (point.breakBefore && current.isNotEmpty()) {
                if (current.size >= 2) segments += current.toList()
                current.clear()
            }
            current += LatLng.from(point.latitude, point.longitude)
        }
        if (current.size >= 2) segments += current
        return segments
    }

    companion object {
        const val EXTRA_STARTED_AT = "started_at"
        const val EXTRA_STARTED_ATS = "started_ats"
    }
}
