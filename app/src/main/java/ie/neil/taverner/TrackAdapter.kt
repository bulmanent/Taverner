package ie.neil.taverner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Typeface
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    private var currentIndex: Int = RecyclerView.NO_POSITION

    var tracks: List<Track> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.title.text = track.name
        val isCurrent = position == currentIndex
        val colorRes = if (isCurrent) R.color.taverner_secondary else R.color.taverner_primary
        holder.title.setTextColor(holder.itemView.context.getColor(colorRes))
        holder.title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = tracks.size

    fun setCurrentIndex(index: Int): Boolean {
        val newIndex = if (index in tracks.indices) index else RecyclerView.NO_POSITION
        if (newIndex == currentIndex) {
            return false
        }
        val oldIndex = currentIndex
        currentIndex = newIndex
        if (oldIndex != RecyclerView.NO_POSITION) {
            notifyItemChanged(oldIndex)
        }
        if (newIndex != RecyclerView.NO_POSITION) {
            notifyItemChanged(newIndex)
        }
        return true
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(android.R.id.text1)
    }
}
