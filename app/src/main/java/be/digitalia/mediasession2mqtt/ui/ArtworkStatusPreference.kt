package be.digitalia.mediasession2mqtt.ui

import android.content.Context
import android.graphics.Bitmap
import android.preference.Preference
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import be.digitalia.mediasession2mqtt.R

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class ArtworkStatusPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var artwork: Bitmap? = null

    init {
        layoutResource = R.layout.preference_artwork_status
    }

    fun setArtwork(bitmap: Bitmap?) {
        artwork = bitmap
        notifyChanged()
    }

    override fun onBindView(view: View) {
        super.onBindView(view)
        val artworkView = view.findViewById<ImageView>(R.id.status_artwork)
        artworkView.setImageBitmap(artwork)
        artworkView.visibility = if (artwork == null) View.GONE else View.VISIBLE
    }
}
