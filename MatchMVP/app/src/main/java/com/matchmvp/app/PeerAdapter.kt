package com.matchmvp.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class UiPeer(
    val uid: String,
    val avatarLabel: String,
    var liked: Boolean = false,
    val hasBadge: Boolean = false
)

class PeerAdapter(
    private val onLikeClicked: (UiPeer) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private val items = mutableListOf<UiPeer>()

    fun submitList(newItems: List<UiPeer>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarText: TextView = itemView.findViewById(R.id.avatarText)
        private val likeBtn: Button = itemView.findViewById(R.id.likeBtn)

        fun bind(peer: UiPeer) {
            val context = itemView.context
            avatarText.text = if (peer.hasBadge) {
                "${context.getString(R.string.badge_prefix)} ${peer.avatarLabel}"
            } else {
                peer.avatarLabel
            }
            likeBtn.text = if (peer.liked) context.getString(R.string.liked_button) else context.getString(R.string.like_button)
            likeBtn.isEnabled = !peer.liked
            likeBtn.setOnClickListener {
                peer.liked = true
                notifyItemChanged(adapterPosition)
                onLikeClicked(peer)
            }
        }
    }
}
