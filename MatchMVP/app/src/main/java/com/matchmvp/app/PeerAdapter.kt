package com.matchmvp.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PeerAdapter(
    private val onLikeClick: (UiPeer) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private val items = mutableListOf<UiPeer>()

    fun submitList(newList: List<UiPeer>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Используем peerTitleText из item_peer.xml
        private val titleText: TextView = itemView.findViewById(R.id.peerTitleText)
        private val likeBtn: Button = itemView.findViewById(R.id.likeBtn)

        fun bind(peer: UiPeer) {
            titleText.text = peer.avatarLabel

            if (peer.liked) {
                likeBtn.text = if (peer.hasBadge) "🔥 MATCH" else "⭐ Лайкнуто"
                likeBtn.isEnabled = false
            } else {
                likeBtn.text = "❤️ Лайк"
                likeBtn.isEnabled = true
                likeBtn.setOnClickListener { onLikeClick(peer) }
            }
        }
    }
}
